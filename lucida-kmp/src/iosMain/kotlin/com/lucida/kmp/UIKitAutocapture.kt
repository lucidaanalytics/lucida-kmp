package com.lucida.kmp

import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSStringFromClass
import platform.Foundation.valueForKey
import platform.UIKit.UIButton
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UISwitch
import platform.UIKit.UITextField
import platform.UIKit.UITextView
import platform.UIKit.UIView

/** Hit-tests the window and builds the tapped view's ancestor chain for [Autocapture]. Main thread only. */
internal object UIKitAutocapture {
    /** [x], [y] in points, [root] coordinates. */
    fun chain(root: UIView, x: Double, y: Double): List<Element> {
        val out = ArrayList<Element>()
        var cur: UIView? = root.hitTest(CGPointMake(x, y), withEvent = null)
        while (cur != null) {
            out.add(element(cur))
            cur = cur.superview
        }
        return out
    }

    fun leafIsToggle(root: UIView, x: Double, y: Double) = root.hitTest(CGPointMake(x, y), withEvent = null) is UISwitch

    private fun element(v: UIView): Element {
        val kind = when (v) {
            is UITextField, is UITextView -> ViewKind.INPUT
            is UIButton -> ViewKind.BUTTON
            is UILabel -> ViewKind.TEXT
            is UIImageView -> ViewKind.IMAGE
            else -> ViewKind.CONTAINER
        }
        val id = v.valueForKey("accessibilityIdentifier") as? String // not surfaced on UIView by the interop
        val cls = NSStringFromClass(v.`class`()!!)
        // Text inputs: never read their text. Others: accessibilityLabel first, then the visible label.
        val label = if (kind == ViewKind.INPUT) null else (v.valueForKey("accessibilityLabel") as? String) ?: when (v) {
            is UIButton -> v.currentTitle
            is UILabel -> v.text
            else -> null
        }
        return Element(kind, id ?: cls.lowercase(), label, id, buildMap { put("class", cls); id?.let { put("id", it) } })
    }
}
