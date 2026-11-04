package com.lucida.kmp

import kotlin.math.hypot

data class RageTapConfig(val enabled: Boolean = true, val minTaps: Int = 3, val windowMs: Long = 1000, val radiusDp: Double = 30.0)

/**
 * Element autocapture (`$autocapture` taps and changes), same wire shape as sdks/android. Off by default.
 * Privacy: text-field values are never collected; [propsAllowList] is the only way extra element props leave the device.
 */
data class AutocaptureConfig(
    val enabled: Boolean = false,
    val ignoreLabels: Set<String> = emptySet(),
    val maxElements: Int = 10,
    val propsAllowList: Set<String> = emptySet(),
    /** A view tagged with this marker (and its subtree) is never captured. */
    val noCaptureMarker: String = "lc-no-capture",
    val rageTap: RageTapConfig = RageTapConfig(),
)

/** One entry in the tapped view's ancestor chain (platform-neutral; the Android and UIKit adapters build it). */
data class Element(val kind: ViewKind, val name: String, val label: String? = null, val tag: String? = null, val props: Map<String, String> = emptyMap())

class Autocapture(private val core: Core, val cfg: AutocaptureConfig) {
    private class Tap(val t: Long, val x: Double, val y: Double)

    private val taps = ArrayDeque<Tap>()
    private val tapsLock = Lock()

    /** [chain] runs from the tapped view up to the root. Returns the `$autocapture` event, or null when skipped. */
    fun tap(chain: List<Element>, x: Double, y: Double): Obj? = emit("touch", chain, x, y)

    fun change(chain: List<Element>): Obj? = emit("change", chain, null, null)

    private fun emit(type: String, chain: List<Element>, x: Double?, y: Double?): Obj? {
        if (!cfg.enabled || chain.any { it.tag == cfg.noCaptureMarker }) return null
        val kept = chain.filter { it.name !in cfg.ignoreLabels && it.label !in cfg.ignoreLabels }.take(cfg.maxElements)
        if (kept.isEmpty()) return null
        val els = kept.map { el ->
            buildMap<String, Any?> {
                put("tag_name", el.name)
                // Input kinds never expose label text: it can echo what the user typed.
                if (el.kind != ViewKind.INPUT && !el.label.isNullOrEmpty()) put("text", el.label.take(255))
                el.props.filterKeys { it in cfg.propsAllowList }.forEach { (k, v) -> put("attr__$k", v) }
            }
        }
        val r = core.capture("\$autocapture", mapOf("\$event_type" to type, "\$elements" to els, "\$el_text" to (els.firstNotNullOfOrNull { it["text"] } ?: "")))
        if (type == "touch" && x != null && y != null) rage(els.first(), x, y)
        return r
    }

    private fun rage(top: Map<String, Any?>, x: Double, y: Double) {
        val rc = cfg.rageTap
        if (!rc.enabled) return
        val now = core.clock.nowMs()
        val near = tapsLock.withLock {
            taps.addLast(Tap(now, x, y))
            while (taps.isNotEmpty() && now - taps.first().t > rc.windowMs) taps.removeFirst()
            val n = taps.count { hypot(it.x - x, it.y - y) <= rc.radiusDp }
            if (n >= rc.minTaps) taps.clear()
            n
        }
        if (near >= rc.minTaps) core.capture("\$rageclick", mapOf("\$event_type" to "touch", "\$tap_count" to near, "\$elements" to listOf(top)))
    }
}
