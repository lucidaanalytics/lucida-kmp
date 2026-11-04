package com.lucida.kmp

import kotlin.jvm.Volatile

/**
 * Collects mobile replay events and ships them to POST /v1/replay?compression=gzip as
 * `{api_key, session_id, window_id, seq, events}`. `seq` is monotonic per window and resets when the
 * session rotates (the next screen capture then takes a fresh full snapshot). Replay consent is
 * required: without it nothing is recorded. [flush] blocks; call it off the main thread.
 *
 * Gates: consent, project kill switch + sample rate ([loadRemote], tightening only), linked flag, pause/stop,
 * screen allow/deny list, and the minimum duration (sealed chunks wait until the session has been recorded
 * that long; a session that ends or rotates sooner is dropped, never sent).
 */
/**
 * Recorder behaviour: [sampleRate] decides per session (stable for the whole session), [throttleMs] spaces screen captures.
 * [minDurationMs] holds chunks back, [useRemoteConfig] fetches `GET /v1/config`, [linkedFlag] starts recording only while that
 * flag is on, [screenAllowList]/[screenDenyList] pick screens (deny wins; empty allow = all).
 */
data class ReplayOptions(
    val sampleRate: Double = 1.0,
    val throttleMs: Long = 0,
    val minDurationMs: Long = 0,
    val useRemoteConfig: Boolean = true,
    val linkedFlag: String? = null,
    val screenAllowList: List<String> = emptyList(),
    val screenDenyList: List<String> = emptyList(),
)

class ReplayRecorder(
    private val core: Core,
    private val options: ReplayOptions = ReplayOptions(),
    private val chunkEvents: Int = 200,
    spoolBudgetBytes: Long = 10L * 1024 * 1024,
    private val flagEnabled: (String) -> Boolean = { false },
) {
    /** Chunks waiting (min duration, offline, upload backoff) live on disk, encrypted with the queue cipher; nothing is dropped silently. */
    private val spool = ReplaySpool(
        core.cfg.queueCipher?.let { EncryptedStore(core.cfg.store, it, setOf("lc_replay"), listOf("lc_replay.")) } ?: core.cfg.store,
        spoolBudgetBytes,
    ) { session, chunks, bytes ->
        core.debugLog("replay spool over budget: dropped session $session ($chunks chunks, $bytes bytes)")
        core.cfg.onDrop(
            listOf(mapOf("event" to "\$replay_session_dropped", "properties" to mapOf("session_id" to session, "chunks" to chunks, "bytes" to bytes))),
            "replay_spool_full",
        )
    }
    private val tries = HashMap<Int, Int>()
    private val sealIo = Lock() // serialises seals (they write the spool); order: sealIo -> lock

    // State lock: guards the buffers below. Takes only Consent.lock (via `enabled`) and no I/O; the session is touched before it is taken.
    private val lock = Lock()
    private val buf = ArrayList<Obj>()
    private var lastSession: String? = null
    private var seq = 0
    private var snapshot: Snapshot? = null
    private var sessionStartMs = 0L
    private var released = false

    /** True when the session rotated since the last chunk: the host should re-capture the screen. */
    @Volatile var needsSnapshot = true
        private set

    @Volatile private var running = true
    @Volatile private var paused = false
    @Volatile private var remote: RemoteReplayConfig? = null
    @Volatile private var remoteLoaded = false
    @Volatile private var screenAllowed = true
    private var lastScreenAt = Long.MIN_VALUE

    /** Manual control: stop() ends recording until start() and drops chunks still waiting for the minimum duration. */
    fun start() {
        running = true
    }

    fun stop() {
        val dropHeld = lock.withLock {
            running = false
            if (!released) buf.clear()
            !released
        }
        if (dropHeld) spool.removeHeld() // the session ends before the minimum: held chunks never go out
    }

    /** Keeps the recorder alive but captures nothing until [resume]. */
    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    val isActive: Boolean get() = enabled && sampled() && flagOk()

    private fun flagOk() = options.linkedFlag?.let { flagEnabled(it) } ?: true

    private fun sampled(): Boolean {
        val rate = ReplayPolicy.sampleRate(options.sampleRate, remote)
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false
        return (core.session.touch().hashCode().toLong() and 0x7fffffffL) % 10_000 / 10_000.0 < rate
    }

    val enabled: Boolean get() = running && !paused && core.consent.has("analytics") && core.consent.has("replay")

    /** Fetch the project's replay config once (blocking; call off the main thread). Unreachable: local settings stand. */
    fun loadRemote() {
        if (!options.useRemoteConfig || remoteLoaded || !core.consent.has("analytics")) return
        remoteLoaded = true
        val key = percentEncode(core.cfg.apiKey)
        val r = runCatching { core.cfg.http.send("GET", "${core.host}/v1/config?api_key=$key") }.getOrNull() ?: return
        if (r.status in 200..299) remote = RemoteReplayConfig.parse(r.body)
    }

    /** Would [screen] be recorded right now (gates + throttle)? Lets a host skip an expensive screenshot. */
    fun shouldCapture(screen: String): Boolean = isActive && ReplayPolicy.screenAllowed(screen, options.screenAllowList, options.screenDenyList) &&
        (needsSnapshot || core.clock.nowMs() - lastScreenAt >= options.throttleMs)

    fun screen(root: ViewNode, screen: String, width: Int, height: Int, frame: ScreenshotFrame? = null) {
        if (!isActive) return // asks the session, the flag callback and consent: outside the lock
        val now = core.clock.nowMs()
        val full = lock.withLock {
            if (!running) return
            screenAllowed = ReplayPolicy.screenAllowed(screen, options.screenAllowList, options.screenDenyList)
            if (!screenAllowed) {
                // Entering a denied screen pauses recording; coming back takes a fresh snapshot and taps there are dropped.
                needsSnapshot = true
                snapshot = null
                return
            }
            if (!needsSnapshot && now - lastScreenAt < options.throttleMs) return
            lastScreenAt = now
            val s = if (frame != null) {
                Wireframe.screenshotSnapshot(frame.jpegBase64, screen, frame.width, frame.height, now)
            } else {
                Wireframe.snapshot(root, screen, width, height, now)
            }
            snapshot = s
            needsSnapshot = false
            buf.addAll(s.events)
            buf.size >= chunkEvents
        }
        if (full) seal()
    }

    fun tap(x: Double, y: Double) {
        val now = core.clock.nowMs()
        val full = lock.withLock {
            val s = snapshot ?: return
            if (!enabled || !screenAllowed) return
            buf.add(Wireframe.tap(s, x, y, now))
            buf.size >= chunkEvents
        }
        if (full) seal()
    }

    /** Seals the buffer into a spooled chunk. Touches the session first (that writes storage); the spool write is serialised by [sealIo]. */
    private fun seal() {
        if (lock.withLock { buf.isEmpty() }) return
        val sid = core.session.touch()
        sealIo.withLock {
            var rotated = false
            val events = lock.withLock {
                if (buf.isEmpty()) return
                val evs = ArrayList(buf)
                buf.clear()
                rotated = lastSession != sid
                if (rotated) {
                    if (lastSession != null) needsSnapshot = true
                    released = false
                    sessionStartMs = core.clock.nowMs()
                }
                lastSession = sid
                evs
            }
            if (rotated) {
                spool.removeHeld(sid) // the previous session ended before the minimum duration
                val next = spool.nextSeq(sid) // resumes after a restart inside the same session
                lock.withLock { seq = next }
            }
            val (n, held) = lock.withLock { seq++ to !released }
            val body = Json.encode(mapOf("session_id" to sid, "window_id" to core.session.windowId, "seq" to n, "events" to events))
            spool.add(sid, n, held, body)
        }
    }

    /** Seal what is buffered and POST every spooled chunk in order; stops at the first failure. */
    fun flush() {
        loadRemote()
        val killed = ReplayPolicy.sampleRate(options.sampleRate, remote) <= 0.0
        if (killed) return clear()
        seal()
        var releasedSession: String? = null
        val ok = lock.withLock {
            val minMs = ReplayPolicy.minDurationMs(options.minDurationMs, remote)
            if (!released && !spool.isEmpty) {
                released = core.clock.nowMs() - sessionStartMs >= minMs
                if (released) releasedSession = lastSession
            }
            released || spool.isEmpty
        }
        releasedSession?.let { spool.release(it) }
        if (!ok) return
        while (true) {
            val e = spool.entries().firstOrNull { !it.held } ?: return
            val stored = spool.read(e)
            if (stored == null) {
                spool.remove(e)
                continue
            }
            // the stored body is a JSON object: splice the api key in without a decode/encode round trip
            val body = Json.encode(mapOf("api_key" to core.cfg.apiKey)).dropLast(1) + "," + stored.drop(1)
            val status = runCatching {
                core.cfg.http.send("POST", "${core.host}/v1/replay?compression=gzip", body, mapOf("Content-Type" to "application/json"), gzip = true).status
            }.getOrDefault(0)
            val giveUp = lock.withLock {
                val n = (tries[e.id] ?: 0) + 1
                tries[e.id] = n
                status in 200..299 || !isRetryable(status) || n > MAX_TRIES
            }
            if (!giveUp) return
            lock.withLock { tries.remove(e.id) }
            spool.remove(e)
        }
    }

    /** Drop everything (consent revoked, reset): the buffer and every spooled chunk. */
    fun clear() {
        lock.withLock {
            buf.clear()
            tries.clear()
            snapshot = null
            needsSnapshot = true
            released = false
        }
        spool.clear()
    }

    private companion object {
        const val MAX_TRIES = 5
    }
}
