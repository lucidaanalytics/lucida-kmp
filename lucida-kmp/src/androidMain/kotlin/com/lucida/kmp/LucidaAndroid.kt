package com.lucida.kmp

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window
import java.lang.ref.WeakReference

/**
 * Android entry point: `LucidaAndroid.init(application, "lc_pk_...", "https://ingest.example.com")` wires the common
 * client to SharedPreferences, HttpURLConnection and the activity lifecycle: `$app_*` lifecycle events, `$screen`
 * per Activity, and (opt-in via [replayMasking]) wireframe replay with tap capture.
 */
object LucidaAndroid {
    @Volatile lateinit var client: Lucida
        private set

    @Volatile private var current = WeakReference<Activity>(null)

    fun init(
        app: Application,
        apiKey: String,
        apiHost: String,
        options: LucidaOptions = LucidaOptions(),
        /** null leaves replay off (no view walking, no touch hook); set to enable wireframe replay with these masks. */
        replayMasking: WireframeMasking? = null,
        captureDeepLinks: Boolean = true,
    ): Lucida {
        attachAndroid(app)
        val c = Lucida.create(apiKey, apiHost, options)
        client = c
        app.registerActivityLifecycleCallbacks(Callbacks(c, replayMasking, captureDeepLinks))
        return c
    }

    /** Call from `onNewIntent` (or anywhere a link arrives) to record `$deep_link_opened`. */
    fun handleDeepLink(intent: Intent?, referrer: String? = null) {
        intent?.data?.let { client.deepLink(it.toString(), referrer) }
    }

    private class Callbacks(private val c: Lucida, private val masking: WireframeMasking?, private val deepLinks: Boolean) : Application.ActivityLifecycleCallbacks {
        private var started = 0
        private var first = true

        override fun onActivityStarted(a: Activity) {
            if (started++ == 0 && !first) c.foregrounded()
            first = false
        }

        override fun onActivityStopped(a: Activity) {
            if (--started == 0) c.backgrounded()
        }

        override fun onActivityResumed(a: Activity) {
            current = WeakReference(a)
            val screen = a.javaClass.simpleName
            if (masking == null) {
                c.screen(screen)
                return
            }
            hook(a.window)
            val decor = a.window.decorView
            decor.post {
                val d = a.resources.displayMetrics.density
                c.captureScreen(screen, Math.round(decor.width / d), Math.round(decor.height / d)) {
                    ViewHierarchy(masking, d).capture(decor, MarkerRegistry.snapshot())
                }
            }
        }

        /** Wrap the window callback once so taps are recorded; the original callback still receives everything. */
        private fun hook(w: Window) {
            val cb = w.callback
            if (cb is TapCallback) return
            w.callback = TapCallback(cb, c, w.context.resources.displayMetrics.density)
        }

        override fun onActivityCreated(a: Activity, s: Bundle?) {
            // s != null means a recreate (rotation): the link was already recorded.
            if (s == null && deepLinks) handleDeepLink(a.intent, a.referrer?.toString())
        }

        override fun onActivityPaused(a: Activity) {
            if (current.get() === a) current = WeakReference(null)
        }

        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    private class TapCallback(private val inner: Window.Callback, private val c: Lucida, private val density: Float) : Window.Callback by inner {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) c.tap(e.rawX / density.toDouble(), e.rawY / density.toDouble())
            return inner.dispatchTouchEvent(e)
        }
    }
}
