package com.lucida.kmp

import kotlinx.cinterop.useContents
import platform.Foundation.NSStringFromClass
import platform.Foundation.valueForKey
import platform.MapKit.MKMapView
import platform.UIKit.UIButton
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UITextField
import platform.UIKit.UITextView
import platform.UIKit.UIView
import platform.WebKit.WKWebView
import kotlin.math.roundToInt

/**
 * UIView tree -> platform-neutral [ViewNode] tree (the pure serializer is [Wireframe] in commonMain). Same privacy rules as the
 * Android walker: text and inputs are masked unless the host opts a view out, secure text fields are never unmasked, web views
 * and maps become empty boxes. Only `UILabel.text`, `UIButton.currentTitle` and text-field/-view text are read; placeholder
 * and attributed content never leave the device. Main thread only.
 */
class UIKitViewHierarchy(private val cfg: WireframeMasking = WireframeMasking()) {
    private var budget = cfg.maxNodes

    /** [regions] are marked regions (points, root coordinates); mask wins over unmask. */
    fun capture(root: UIView, regions: List<MarkerRegion> = emptyList()): ViewNode {
        budget = cfg.maxNodes
        val fallback = root.frame.useContents { ViewNode(ViewKind.CONTAINER, 0, 0, size.width.roundToInt(), size.height.roundToInt()) }
        return MarkerRegions.apply(node(root, 0, false, false, atRoot = true) ?: fallback, regions)
    }

    private fun node(v: UIView, depth: Int, parentMask: Boolean, parentUnmask: Boolean, atRoot: Boolean = false): ViewNode? {
        val tag = v.valueForKey("accessibilityIdentifier") as? String // UIAccessibilityIdentification is not surfaced on UIView by the interop
        if ((v.hidden && !atRoot) || v.alpha < 0.01 || tag == LcTag.IGNORE || budget <= 0) return null
        budget--
        val kind = when (v) {
            is UITextField, is UITextView -> ViewKind.INPUT
            is UIButton -> ViewKind.BUTTON
            is UILabel -> ViewKind.TEXT
            is UIImageView -> ViewKind.IMAGE
            else -> ViewKind.CONTAINER
        }
        val secure = (v as? UITextField)?.secureTextEntry == true || (v as? UITextView)?.secureTextEntry == true
        val sandboxed = cfg.maskSandboxedViews && (v is WKWebView || v is MKMapView)
        val block = tag == LcTag.BLOCK || sandboxed
        val mask = parentMask || tag == LcTag.MASK
        val unmask = !mask && (parentUnmask || tag == LcTag.UNMASK || tag == "lucida-unmask")
        val kids = if (!block && depth < cfg.maxDepth) {
            v.subviews.mapNotNull { (it as? UIView)?.let { c -> node(c, depth + 1, mask, unmask) } }
        } else {
            emptyList()
        }
        val text = when (v) {
            is UILabel -> v.text
            is UIButton -> v.currentTitle
            is UITextField -> v.text
            is UITextView -> v.text
            else -> null
        }
        val sensitive = tag == "lucida-sensitive"
        val (x, y, w, h) = v.frame.useContents { listOf(origin.x.roundToInt(), origin.y.roundToInt(), size.width.roundToInt(), size.height.roundToInt()) }
        return ViewNode(
            kind = kind, x = x, y = y, width = w, height = h,
            text = if (block || secure) null else text,
            name = null,
            maskText = mask || sensitive || (cfg.maskAllText && !unmask),
            maskInput = mask || secure || sensitive || (cfg.maskAllTextInputs && !unmask),
            maskImage = mask || (cfg.maskAllImages && !unmask),
            block = block,
            neverUnmask = secure,
            opaque = NSStringFromClass(v.`class`()!!).contains("HostingView"),
            children = kids,
        )
    }
}
