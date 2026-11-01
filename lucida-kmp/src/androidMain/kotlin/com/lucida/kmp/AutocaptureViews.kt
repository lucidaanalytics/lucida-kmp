package com.lucida.kmp

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

/** Hit-tests the window and builds the tapped view's ancestor chain for [com.lucida.kmp.Autocapture]. */
internal object AutocaptureViews {
    fun chain(root: View, rawX: Float, rawY: Float): List<Element> {
        val out = ArrayList<Element>()
        var cur: View? = hit(root, rawX.toInt(), rawY.toInt())
        while (cur != null) {
            out.add(element(cur))
            cur = cur.parent as? View
        }
        return out
    }

    fun leafIsCompoundButton(root: View, rawX: Float, rawY: Float) = hit(root, rawX.toInt(), rawY.toInt()) is CompoundButton

    private fun hit(v: View, x: Int, y: Int): View? {
        if (v.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (x < loc[0] || x >= loc[0] + v.width || y < loc[1] || y >= loc[1] + v.height) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) hit(v.getChildAt(i), x, y)?.let { return it }
        }
        return v
    }

    private fun element(v: View): Element {
        val kind = when (v) {
            is EditText -> ViewKind.INPUT
            is Button -> ViewKind.BUTTON
            is TextView -> ViewKind.TEXT
            is ImageView -> ViewKind.IMAGE
            else -> ViewKind.CONTAINER
        }
        val id = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() else null
        val cls = v.javaClass.simpleName
        // EditText: never read its text. Others: contentDescription first, then the visible label.
        val label = if (v is EditText) null else (v.contentDescription?.toString() ?: (v as? TextView)?.text?.toString())
        return Element(kind, id ?: cls.lowercase(), label, v.tag as? String, buildMap { put("class", cls); id?.let { put("id", it) } })
    }
}
