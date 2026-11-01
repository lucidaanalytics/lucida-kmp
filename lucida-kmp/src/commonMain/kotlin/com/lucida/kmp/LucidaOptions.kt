package com.lucida.kmp

/** Everything beyond the required `create` arguments. Defaults match the Android and iOS SDKs. */
data class LucidaOptions(
    /** OPT_IN: every consent category starts denied and nothing is persisted until [Lucida.optIn] / [Lucida.setConsent]. */
    val consentMode: ConsentMode = ConsentMode.OPT_OUT,
    val beforeSend: List<BeforeSend> = emptyList(),
    val personProfiles: PersonProfiles = PersonProfiles.ALWAYS,
    val disableGeoip: Boolean = false,
    val disabled: Boolean = false,
    val debug: Boolean = false,
    val bootstrap: Bootstrap? = null,
    val flags: FlagOptions = FlagOptions(),
    val flagDefinitions: List<Obj> = emptyList(),
    val replay: ReplayOptions = ReplayOptions(),
    /** `$autocapture` taps/changes and `$rageclick`; off by default. The Android and iOS adapters build the element chain. */
    val autocapture: AutocaptureConfig = AutocaptureConfig(),
    /** In-app guides (`GET /v1/guides`); off by default. Android and iOS default the presenter to a native alert. */
    val guides: GuidesConfig = GuidesConfig(),
    val reuseAnonymousId: Boolean = false,
    val anonymousIdProvider: (() -> String)? = null,
    val queueCipher: QueueCipher? = null,
    val tracingHeaders: List<String> = emptyList(),
    val flushAt: Int = 20,
    val flushIntervalMs: Long = 5000,
    val maxQueueSize: Int = 1000,
    val maxBatchSize: Int = 100,
    val maxRetries: Int = 5,
    val retryDelayMs: Long = 1000,
    val requestTimeoutMs: Int = 15_000,
    /** Return false to hold events in the queue (for example Wi-Fi-only data mode). */
    val networkAllowed: () -> Boolean = { true },
    val sessionIdleMs: Long = 30 * 60_000,
    val sessionMaxMs: Long = 24 * 3_600_000,
) {
    internal fun coreConfig(apiKey: String, apiHost: String, platform: Platform) = CoreConfig(
        apiKey, apiHost, platform.http, platform.store, consentMode = consentMode,
        queue = {
            QueueConfig(
                it, apiKey, flushAt = flushAt, flushIntervalMs = flushIntervalMs, maxQueueSize = maxQueueSize,
                maxRetries = maxRetries, maxBatchSize = maxBatchSize, retryBaseMs = retryDelayMs, networkAllowed = networkAllowed,
            )
        },
        beforeSend = beforeSend, personProfiles = personProfiles, disableGeoip = disableGeoip, disabled = disabled,
        sessionIdleMs = sessionIdleMs, sessionMaxMs = sessionMaxMs, reuseAnonymousId = reuseAnonymousId,
        anonymousIdProvider = anonymousIdProvider, bootstrap = bootstrap, queueCipher = queueCipher,
        tracingHeaders = tracingHeaders, debug = debug,
    )
}
