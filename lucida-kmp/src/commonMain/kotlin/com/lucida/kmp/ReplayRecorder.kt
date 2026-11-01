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
    private val maxPendingChunks: Int = 20,
    private val flagEnabled: (String) -> Boolean = { false },
) {
    private class Chunk(val session: String, val seq: Int, val events: List<Obj>, var tries: Int = 0)

    private val buf = ArrayList<Obj>()
    private val pending = ArrayDeque<Chunk>()
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
        locked {
            running = false
            dropUnreleased()
        }
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
        locked {
            if (!isActive) return
            screenAllowed = ReplayPolicy.screenAllowed(screen, options.screenAllowList, options.screenDenyList)
            if (!screenAllowed) {
                // Entering a denied screen pauses recording; coming back takes a fresh snapshot and taps there are dropped.
                needsSnapshot = true
                snapshot = null
                return
            }
            val now = core.clock.nowMs()
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
            sealIfFull()
        }
    }

    fun tap(x: Double, y: Double) {
        locked {
            val s = snapshot ?: return
            if (!enabled || !screenAllowed) return
            buf.add(Wireframe.tap(s, x, y, core.clock.nowMs()))
            sealIfFull()
        }
    }

    private fun sealIfFull() {
        if (buf.size >= chunkEvents) seal()
    }

    private fun dropUnreleased() {
        if (!released) {
            buf.clear()
            pending.clear()
        }
    }

    private fun seal() {
        if (buf.isEmpty()) return
        val sid = core.session.touch()
        if (lastSession != sid) {
            if (lastSession != null) {
                seq = 0
                needsSnapshot = true
                if (!released) pending.removeAll { it.session == lastSession }  // session ended before the minimum duration
            }
            released = false
            sessionStartMs = core.clock.nowMs()
        }
        lastSession = sid
        pending.addLast(Chunk(sid, seq++, ArrayList(buf)))
        buf.clear()
        while (pending.size > maxPendingChunks) pending.removeFirst()
    }

    /** Seal what is buffered and POST every pending chunk in order; stops at the first failure. */
    fun flush() {
        loadRemote()
        val killed = ReplayPolicy.sampleRate(options.sampleRate, remote) <= 0.0
        val ok = locked {
            seal()
            if (killed) {
                clear()
                false
            } else {
                val minMs = ReplayPolicy.minDurationMs(options.minDurationMs, remote)
                if (!released && pending.isNotEmpty()) released = core.clock.nowMs() - sessionStartMs >= minMs
                released || pending.isEmpty()
            }
        }
        if (!ok) return
        while (true) {
            val c = locked { pending.firstOrNull() } ?: return
            val body = Json.encode(
                mapOf("api_key" to core.cfg.apiKey, "session_id" to c.session, "window_id" to core.session.windowId, "seq" to c.seq, "events" to c.events),
            )
            val status = runCatching {
                core.cfg.http.send("POST", "${core.host}/v1/replay?compression=gzip", body, mapOf("Content-Type" to "application/json"), gzip = true).status
            }.getOrDefault(0)
            locked {
                if (status in 200..299 || !isRetryable(status) || ++c.tries > MAX_TRIES) {
                    pending.remove(c)
                } else {
                    return
                }
            }
        }
    }

    /** Drop everything (consent revoked). */
    fun clear() {
        locked {
            buf.clear()
            pending.clear()
            snapshot = null
            needsSnapshot = true
            released = false
        }
    }

    private companion object {
        const val MAX_TRIES = 5
    }
}
