package com.lucida.kmp

/**
 * Facade: the one object a host app holds. Wires core + flags + lifecycle + replay.
 * Network calls (flags, replay flush) run on a private background thread ([Scheduler]).
 * Build one with [Lucida.create] (platform defaults) or the constructor (custom ports, tests).
 */
class Lucida(
    config: CoreConfig,
    appVersion: String,
    appBuild: String? = null,
    flagDefinitions: List<Obj> = emptyList(),
    deviceProperties: Obj = emptyMap(),
    flagOptions: FlagOptions = FlagOptions(),
    replayOptions: ReplayOptions = ReplayOptions(),
    autocaptureConfig: AutocaptureConfig = AutocaptureConfig(),
    guidesConfig: GuidesConfig = GuidesConfig(),
    private val io: Scheduler = newScheduler("lucida-io"),
) {
    val core = Core(config)
    private val preload = flagOptions.preload
    val flags = Flags(core, flagDefinitions, deviceProperties, appVersion, flagOptions)
    val lifecycle = Lifecycle(core, appVersion, appBuild)
    val replay = ReplayRecorder(core, replayOptions, flagEnabled = { flags.isEnabled(it) })
    val autocapture = Autocapture(core, autocaptureConfig)
    val guides = GuidesService(core, guidesConfig) { flags.isEnabled(it) }

    /** Emit launch lifecycle events, then load remote replay config and flags in the background. */
    fun start() {
        lifecycle.launched()
        io.execute { reloadAll() }
    }

    private fun reloadAll() {
        replay.loadRemote()
        if (preload) flags.reload()
        guides.reload()
    }

    fun capture(event: String, properties: Obj = emptyMap(), set: Obj? = null, setOnce: Obj? = null) = core.capture(event, properties, set, setOnce)
    fun screen(name: String, properties: Obj = emptyMap()): Obj? = core.screen(name, properties).also { guides.show(name) }

    fun identify(id: String, set: Obj? = null, setOnce: Obj? = null) {
        core.identify(id, set, setOnce)
        io.execute { reloadAll() }
    }

    fun alias(alias: String) = core.alias(alias)
    fun group(type: String, key: String, properties: Obj? = null) = core.group(type, key, properties)
    fun setPersonProperties(set: Obj? = null, setOnce: Obj? = null) = core.setPersonProperties(set, setOnce)
    fun register(props: Obj) = core.register(props)
    fun registerOnce(props: Obj) = core.registerOnce(props)
    fun unregister(key: String) = core.unregister(key)
    fun setConsent(update: Map<String, Boolean>) {
        core.setConsent(update)
        if (!core.consent.has("analytics") || !core.consent.has("replay")) replay.clear()
    }
    fun isOptedOut() = core.isOptedOut
    fun getAnonymousId() = core.anonymousId
    val distinctId get() = core.distinctId

    /** Current session id; touching it extends the idle window. */
    val sessionId: String get() = core.session.touch()

    fun debug(on: Boolean) {
        core.debug = on
    }

    fun deepLink(url: String, referrer: String? = null) = core.deepLink(url, referrer)

    /** Host-header helper for your own backend calls; see [TracingHeaders]. */
    val tracing get() = core.tracing

    fun reset() {
        replay.clear()
        core.reset()
        io.execute { reloadAll() }
    }

    fun optIn() {
        core.optIn()
        io.execute { reloadAll() }
    }

    fun optOut() {
        core.optOut()
        replay.clear()
    }

    /** Manual replay control: consent, sampling, the linked flag and screen lists still apply. */
    fun startReplay() = replay.start()
    fun stopReplay() = replay.stop()
    fun pauseReplay() = replay.pause()
    fun resumeReplay() = replay.resume()
    val isReplayActive get() = replay.isActive

    /**
     * Record one screen: `$screen` plus (when the replay gates pass) a wireframe of [walk]'s tree. The walker only runs
     * when the recorder will use its result, so a gated or throttled screen costs nothing. Call on the UI thread.
     * Screenshot mode: [shot] turns the walked (marker-applied) tree into an already-masked frame; a null frame
     * (nothing drawable yet) skips this screen rather than falling back to an unmasked wireframe.
     */
    fun captureScreen(screen: String, width: Int, height: Int, shot: ((ViewNode) -> ScreenshotFrame?)? = null, walk: () -> ViewNode) {
        this.screen(screen)
        if (!replay.shouldCapture(screen)) {
            replay.screen(ViewNode(ViewKind.CONTAINER, 0, 0, 0, 0), screen, 0, 0) // lets a denied screen pause recording
            return
        }
        val tree = walk()
        val frame = shot?.let { it(tree) ?: return }
        replay.screen(tree, screen, width, height, frame)
    }

    /** A tap in dp (decor/window coordinates). */
    fun tap(x: Double, y: Double) = replay.tap(x, y)

    fun foregrounded() = lifecycle.foregrounded()

    fun backgrounded() {
        lifecycle.backgrounded()
        io.execute { replay.flush() }
    }

    fun flush() {
        core.flush()
        io.execute { replay.flush() }
    }

    /** Block until background work submitted so far has finished (tests, shutdown hooks). */
    fun idle() {
        io.idle()
        core.queue.idle()
    }

    companion object {
        /**
         * Platform defaults for storage, transport and device info. On Android call `attachAndroid(app)` first.
         * Registers `$os`, `$os_version` and `$device_model` as super properties and starts the client.
         */
        fun create(apiKey: String, apiHost: String, options: LucidaOptions = LucidaOptions(), platform: Platform = platformDefaults(options.requestTimeoutMs)): Lucida {
            val d = platform.device
            val c = Lucida(
                options.coreConfig(apiKey, apiHost, platform),
                appVersion = d.appVersion,
                appBuild = d.appBuild,
                flagDefinitions = options.flagDefinitions,
                deviceProperties = buildMap {
                    put("os", d.os); put("os_version", d.osVersion); put("model", d.model)
                    d.manufacturer?.let { put("manufacturer", it) }
                },
                flagOptions = options.flags,
                replayOptions = options.replay,
                autocaptureConfig = options.autocapture,
                guidesConfig = options.guides,
            )
            c.core.register(mapOf("\$os" to d.os, "\$os_version" to d.osVersion, "\$device_model" to d.model))
            c.start()
            return c
        }
    }
}
