package com.lucida.kmp

/** The slice of `GET /v1/config` that governs replay (the document the browser SDK reads). */
data class RemoteReplayConfig(val sessionRecording: Boolean = true, val sampleRate: Double? = null, val minDurationMs: Long? = null) {
    companion object {
        fun parse(json: String): RemoteReplayConfig? {
            val o = runCatching { Json.decodeObj(json) }.getOrNull() ?: return null
            return RemoteReplayConfig(
                sessionRecording = o["session_recording"] as? Boolean ?: true,
                sampleRate = (o["replay_sample_rate"] as? Number)?.toDouble(),
                minDurationMs = (o["replay_min_duration_ms"] as? Number)?.toLong(),
            )
        }
    }
}

/** Pure replay gating rules. Remote config can only tighten what the app configured locally. */
object ReplayPolicy {
    /** Deny wins; an empty allow list allows every screen. */
    fun screenAllowed(screen: String, allow: List<String>, deny: List<String>) = screen !in deny && (allow.isEmpty() || screen in allow)

    fun sampleRate(local: Double, remote: RemoteReplayConfig?): Double =
        if (remote == null) local else if (remote.sessionRecording) minOf(local, remote.sampleRate ?: 1.0) else 0.0

    fun minDurationMs(local: Long, remote: RemoteReplayConfig?): Long = maxOf(local, remote?.minDurationMs ?: 0L)
}

/** Absolute rectangle in dp (decor coordinates). */
data class MaskRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Paint order: [mask] first, then [unmask] regions are restored from the original pixels, then [sensitive] last (always wins). */
data class ScreenshotMaskPlan(val mask: List<MaskRect>, val unmask: List<MaskRect>, val sensitive: List<MaskRect>)

/** An already-masked JPEG frame (base64, from android.util.Base64 so minSdk 23 works); dimensions are dp. */
class ScreenshotFrame(val jpegBase64: String, val width: Int, val height: Int)

enum class MarkerRole { MASK, UNMASK }

/** A Compose (or any non-View) region the app marked; bounds in dp relative to the decor view. */
data class MarkerRegion(val role: MarkerRole, val rect: MaskRect)

/**
 * Registry the Compose modifiers write to and the view walker reads (Compose content is not in the View tree).
 * Entries are keyed by an opaque owner and removed when the composable leaves composition.
 */
object MarkerRegistry {
    private val regions = LinkedHashMap<Any, MarkerRegion>()

    fun set(owner: Any, region: MarkerRegion) {
        locked {
            regions[owner] = region
        }
    }

    fun remove(owner: Any) {
        locked {
            regions.remove(owner)
        }
    }

    fun snapshot(): List<MarkerRegion> = locked { regions.values.toList() }

    fun clear() = locked { regions.clear() }
}

/** Marker precedence matches iOS and UIKit/View markers: mask always wins over unmask; password-like nodes are never unmasked. */
object MarkerRegions {
    private fun inside(r: MaskRect, g: MaskRect) =
        r.x >= g.x - 1 && r.y >= g.y - 1 && r.x + r.width <= g.x + g.width + 1 && r.y + r.height <= g.y + g.height + 1

    fun apply(root: ViewNode, regions: List<MarkerRegion>): ViewNode {
        if (regions.isEmpty()) return root
        val masks = regions.filter { it.role == MarkerRole.MASK }.map { it.rect }
        val unmasks = regions.filter { it.role == MarkerRole.UNMASK }.map { it.rect }
        fun walk(n: ViewNode, ox: Int, oy: Int, isRoot: Boolean): ViewNode {
            val left = if (isRoot) 0 else ox + n.x
            val top = if (isRoot) 0 else oy + n.y
            val r = MaskRect(left, top, n.width, n.height)
            val kids = n.children.map { walk(it, left, top, false) }
            return when {
                masks.any { inside(r, it) } -> n.copy(maskText = true, maskInput = true, maskImage = true, children = kids)
                !n.neverUnmask && unmasks.any { inside(r, it) } -> n.copy(maskText = false, maskInput = false, maskImage = false, children = kids)
                else -> n.copy(children = kids)
            }
        }
        return walk(root, 0, 0, true)
    }
}

/** Derives the screenshot plan from the (marker-applied) view tree and the raw marker regions. */
object ScreenshotMasks {
    fun plan(root: ViewNode, regions: List<MarkerRegion> = emptyList()): ScreenshotMaskPlan {
        val mask = ArrayList<MaskRect>()
        val unmask = ArrayList<MaskRect>()
        val sensitive = ArrayList<MaskRect>()
        fun walk(n: ViewNode, ox: Int, oy: Int, isRoot: Boolean) {
            val left = if (isRoot) 0 else ox + n.x
            val top = if (isRoot) 0 else oy + n.y
            val r = MaskRect(left, top, n.width, n.height)
            val hidden = when (n.kind) {
                ViewKind.INPUT -> n.maskInput
                ViewKind.IMAGE -> n.maskImage
                else -> n.maskText && (!n.text.isNullOrEmpty() || n.opaque)
            }
            val visibleLeaf = when (n.kind) {
                ViewKind.INPUT -> !n.maskInput
                ViewKind.IMAGE -> !n.maskImage
                else -> !n.maskText && (!n.text.isNullOrEmpty() || n.opaque)
            }
            when {
                n.block || (n.kind == ViewKind.INPUT && n.neverUnmask) -> sensitive.add(r)
                hidden -> mask.add(r)
                visibleLeaf -> unmask.add(r)
            }
            n.children.forEach { walk(it, left, top, false) }
        }
        walk(root, 0, 0, true)
        regions.forEach { if (it.role == MarkerRole.MASK) sensitive.add(it.rect) else unmask.add(it.rect) }
        return ScreenshotMaskPlan(mask, unmask, sensitive)
    }
}
