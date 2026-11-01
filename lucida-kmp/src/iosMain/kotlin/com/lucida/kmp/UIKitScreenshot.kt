package com.lucida.kmp

import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGContextClipToRect
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.base64EncodedStringWithOptions
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import platform.UIKit.UIView
import kotlin.math.roundToInt

/**
 * Screenshot mode on iOS: draws [root] (the key window) at scale 1, then paints the mask plan on a second canvas in the
 * sdks/ios order (mask rects, unmask rects restored from the original, sensitive rects last) and only then encodes JPEG,
 * so masked pixels never exist in the output. Main thread only.
 */
object UIKitScreenshot {
    fun capture(root: UIView, plan: ScreenshotMaskPlan, quality: Int): ScreenshotFrame? {
        val (w, h) = root.bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return null
        val bounds = CGRectMake(0.0, 0.0, w, h)
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), true, 1.0) // replay does not need retina pixels
        val raw: UIImage? = try {
            root.drawViewHierarchyInRect(bounds, afterScreenUpdates = false)
            UIGraphicsGetImageFromCurrentImageContext()
        } finally {
            UIGraphicsEndImageContext()
        }
        if (raw == null) return null
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), true, 1.0)
        try {
            val ctx = UIGraphicsGetCurrentContext() ?: return null
            raw.drawInRect(bounds)
            UIColor.blackColor.setFill()
            plan.mask.forEach { UIRectFill(rect(it)) }
            plan.unmask.forEach {
                CGContextSaveGState(ctx)
                CGContextClipToRect(ctx, rect(it))
                raw.drawInRect(bounds)
                CGContextRestoreGState(ctx)
            }
            UIColor.blackColor.setFill()
            plan.sensitive.forEach { UIRectFill(rect(it)) }
            val masked = UIGraphicsGetImageFromCurrentImageContext() ?: return null
            val jpeg = UIImageJPEGRepresentation(masked, quality.coerceIn(1, 100) / 100.0) ?: return null
            return ScreenshotFrame(jpeg.base64EncodedStringWithOptions(0u), w.roundToInt(), h.roundToInt())
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    private fun rect(r: MaskRect) = CGRectMake(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble())
}
