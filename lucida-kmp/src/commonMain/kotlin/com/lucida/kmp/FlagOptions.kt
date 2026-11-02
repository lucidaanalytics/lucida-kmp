package com.lucida.kmp

/** Identity and flag values known before the first network response (server-rendered, or from a previous launch). */
data class Bootstrap(
    val distinctId: String? = null,
    val isIdentified: Boolean = false,
    val flags: Obj = emptyMap(),
    val payloads: Obj = emptyMap(),
)

data class FlagOptions(
    /** Emit `$feature_flag_called` when a flag is read. */
    val sendFeatureFlagEvent: Boolean = true,
    /** false: [Lucida.start] does not load flags; call `flags.reload()` when the app needs them. */
    val preload: Boolean = true,
    /** Environment tags sent with the flag request; the server returns only flags whose tags match. */
    val evaluationContexts: List<String> = emptyList(),
    /** Attach `$feature/<key>` and `$active_feature_flags` to every event (never `$snapshot`); the caller's properties win. */
    val sendFeatureFlags: Boolean = true,
    /** `{flagKey: value}` served with reason `fallback` when a flag has no value (missing, failed load, cold start); no events. */
    val fallbacks: Obj = emptyMap(),
)

/** Why a flag read returned what it did; [reason] is the raw Lucida reason (`condition_match`, `kill_switch`, `fallback`, ...). */
data class FlagDetails(
    val value: Any?,
    val payload: Any?,
    val variant: String?,
    val reason: String,
    /** -1 when no condition decided the result. */
    val conditionIndex: Int,
    val requestId: String?,
    val evaluatedLocally: Boolean,
)

enum class FlagsStatus { CHANGED, STALE, ERROR }
