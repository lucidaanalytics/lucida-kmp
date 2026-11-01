package com.lucida.kmp

import android.text.InputType
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

private val PASSWORD_VARIATIONS = setOf(
    InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
)

/** android.view.View tree -> platform-neutral [ViewNode] tree (the pure serializer is [Wireframe] in commonMain). */
class ViewHierarchy(private val cfg: WireframeMasking, private val density: Float) {
    private var budget = cfg.maxNodes

    private fun isPassword(v: View): Boolean {
        val t = (v as? EditText)?.inputType ?: return false
        val cls = t and InputType.TYPE_MASK_CLASS
        val variation = t and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun dp(px: Int) = Math.round(px / density)

    /** [regions] are marked regions (dp, decor coordinates); mask wins over unmask. */
    fun capture(root: View, regions: List<MarkerRegion> = emptyList()): ViewNode =
        MarkerRegions.apply(node(root, 0, false, false) ?: ViewNode(ViewKind.CONTAINER, 0, 0, dp(root.width), dp(root.height)), regions)

    private fun node(v: View, depth: Int, parentMask: Boolean, parentUnmask: Boolean): ViewNode? {
        if (v.visibility != View.VISIBLE || v.tag == LcTag.IGNORE || budget <= 0) return null
        budget--
        val kind = when (v) {
            is EditText -> ViewKind.INPUT
            is Button -> ViewKind.BUTTON
            is TextView -> ViewKind.TEXT
            is ImageView -> ViewKind.IMAGE
            else -> ViewKind.CONTAINER
        }
        val block = v.tag == LcTag.BLOCK || (cfg.maskSandboxedViews && (v is WebView || v is SurfaceView || v is TextureView))
        // Markers apply to the whole subtree; mask beats unmask at any level.
        val mask = parentMask || v.tag == LcTag.MASK
        val unmask = !mask && (parentUnmask || v.tag == LcTag.UNMASK)
        val children = if (v is ViewGroup && !block && depth < cfg.maxDepth) {
            (0 until v.childCount).mapNotNull { node(v.getChildAt(it), depth + 1, mask, unmask) }
        } else {
            emptyList()
        }
        return ViewNode(
            kind = kind,
            x = dp(v.left),
            y = dp(v.top),
            width = dp(v.width),
            height = dp(v.height),
            // Hint text and password content never leave the device: only TextView.text is read.
            text = if (block) null else (v as? TextView)?.text?.toString(),
            name = v.javaClass.simpleName.lowercase(),
            maskText = mask || (cfg.maskAllText && !unmask),
            maskInput = mask || isPassword(v) || (cfg.maskAllTextInputs && !unmask),
            maskImage = mask || (cfg.maskAllImages && !unmask),
            block = block,
            neverUnmask = isPassword(v),
            opaque = v.javaClass.name.contains("ComposeView"),
            children = children,
        )
    }
}
