package com.lucida.kmp

/**
 * App lifecycle events: `$app_installed` / `$app_updated` (version change) on launch, `$app_opened` on
 * launch and foreground, `$app_backgrounded` (and a flush) on background.
 */
class Lifecycle(private val core: Core, private val version: String, private val build: String? = null) {
    private val app: Obj get() = buildMap { put("\$app_version", version); build?.let { put("\$app_build", it) } }

    fun launched() {
        val prev = core.storage.get(KEY)
        if (prev == null) core.capture("\$app_installed", app)
        else if (prev != version) core.capture("\$app_updated", app + ("\$previous_app_version" to prev))
        core.storage.set(KEY, version)
        core.capture("\$app_opened", app + ("from_background" to false))
    }

    fun foregrounded() {
        core.capture("\$app_opened", app + ("from_background" to true))
    }

    fun backgrounded() {
        core.capture("\$app_backgrounded", app)
        core.flush()
    }

    private companion object {
        const val KEY = "lc_app_version"
    }
}
