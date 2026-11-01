package com.lucida.kmp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Guide steps as an android.app.AlertDialog on the current Activity, always on the main thread.
 * Text is set as plain strings, never markup. A dialog shows at most three buttons (the first three).
 */
class AlertGuidePresenter(private val activity: () -> Activity?) : GuidePresenter {
    private val main = Handler(Looper.getMainLooper())

    override fun present(guide: MobileGuide, step: Int, buttons: List<GuideButton>, onAction: (GuideButton?) -> Unit) {
        main.post {
            val a = activity()
            if (a == null || a.isFinishing) return@post onAction(null)
            var done = false
            val finish = { b: GuideButton? ->
                if (!done) {
                    done = true
                    onAction(b)
                }
            }
            val s = guide.steps[step]
            val d = AlertDialog.Builder(a).setTitle(s.title ?: guide.name).setMessage(s.body)
            buttons.getOrNull(0)?.let { b -> d.setPositiveButton(b.label) { _, _ -> finish(b) } }
            buttons.getOrNull(1)?.let { b -> d.setNegativeButton(b.label) { _, _ -> finish(b) } }
            buttons.getOrNull(2)?.let { b -> d.setNeutralButton(b.label) { _, _ -> finish(b) } }
            d.setOnCancelListener { finish(null) } // back / tap outside
            d.show()
        }
    }

    override fun open(url: String) {
        main.post { activity()?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
