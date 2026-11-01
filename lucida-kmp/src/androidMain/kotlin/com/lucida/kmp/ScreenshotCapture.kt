package com.lucida.kmp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Draws the Activity's decor view into a bitmap, paints the mask plan, and only then encodes JPEG, so masked pixels
 * never exist in the output. Order: mask rects, unmask rects restored from the original, sensitive rects last.
 * Must run on the main thread. SurfaceView/TextureView content is not drawn by [View.draw]; it shows black.
 */
object ScreenshotCapture {
    fun capture(activity: Activity, plan: ScreenshotMaskPlan, quality: Int): ScreenshotFrame? {
        val decor = activity.window.decorView
        if (decor.width <= 0 || decor.height <= 0) return null
        val d = activity.resources.displayMetrics.density
        val raw = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.RGB_565)
        val out = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.RGB_565)
        try {
            decor.draw(Canvas(raw))
            val c = Canvas(out)
            c.drawBitmap(raw, 0f, 0f, null)
            val black = Paint().apply { color = Color.BLACK }
            plan.mask.forEach { c.drawRect(px(it, d), black) }
            plan.unmask.forEach { r -> px(r, d).let { c.drawBitmap(raw, it, it, null) } }
            plan.sensitive.forEach { c.drawRect(px(it, d), black) }
            val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            return ScreenshotFrame(Base64.encodeToString(bytes, Base64.NO_WRAP), Math.round(decor.width / d), Math.round(decor.height / d))
        } finally {
            raw.recycle()
            out.recycle()
        }
    }

    private fun px(r: MaskRect, d: Float) = Rect(Math.round(r.x * d), Math.round(r.y * d), Math.round((r.x + r.width) * d), Math.round((r.y + r.height) * d))
}
