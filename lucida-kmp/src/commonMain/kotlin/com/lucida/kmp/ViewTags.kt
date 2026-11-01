package com.lucida.kmp

/**
 * Privacy knobs for the platform view walkers. Everything is masked by default; password inputs are masked
 * regardless. The walkers turn these into the per-node flags of [ViewNode].
 */
data class WireframeMasking(
    val maskAllText: Boolean = true,
    val maskAllTextInputs: Boolean = true,
    val maskAllImages: Boolean = true,
    /** Web views, maps, video surfaces (content we cannot see into) are replaced by an empty box. */
    val maskSandboxedViews: Boolean = true,
    val maxDepth: Int = 40,
    val maxNodes: Int = 1500,
)

/**
 * View tags a host can set (Android `view.tag`, iOS `accessibilityIdentifier`):
 * `lc-block` replaces the view and its subtree with an empty box, `lc-ignore` omits it,
 * `lc-mask` masks its text, input value and image even when the global switches are off,
 * `lc-unmask` shows them even when the switches are on (never password inputs).
 * iOS also accepts the Swift SDK's `lucida-sensitive` (= block text) and `lucida-unmask`.
 */
object LcTag {
    const val BLOCK = "lc-block"
    const val IGNORE = "lc-ignore"
    const val MASK = "lc-mask"
    const val UNMASK = "lc-unmask"
}
