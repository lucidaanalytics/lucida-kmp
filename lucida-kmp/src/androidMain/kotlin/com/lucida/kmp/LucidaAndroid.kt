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
 * per Activity, and (opt-in via [replayMasking]) wireframe or screenshot replay with tap capture. `options.autocapture`
 * adds `$autocapture` taps; `options.guides` shows in-app guides as an AlertDialog unless a presenter is set.
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
        if (options.guides.presenter == null) options.guides.presenter = AlertGuidePresenter { current.get() }
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
            if (masking != null || c.autocapture.cfg.enabled) hook(a.window)
            if (masking == null) {
                c.screen(screen)
                return
            }
            val decor = a.window.decorView
            decor.post {
                val d = a.resources.displayMetrics.density
                val regions = MarkerRegistry.snapshot()
                val shot = if (masking.mode == ReplayMode.SCREENSHOT) {
                    { tree: ViewNode -> ScreenshotCapture.capture(a, ScreenshotMasks.plan(tree, regions), masking.screenshotQuality) }
                } else {
                    null
                }
                c.captureScreen(screen, Math.round(decor.width / d), Math.round(decor.height / d), shot) {
                    ViewHierarchy(masking, d).capture(decor, regions)
                }
            }
        }

        /** Wrap the window callback once so taps are recorded; the original callback still receives everything. */
        private fun hook(w: Window) {
            val cb = w.callback
            if (cb is TapCallback) return
            w.callback = TapCallback(cb, c, w, masking != null)
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

    private class TapCallback(private val inner: Window.Callback, private val c: Lucida, private val w: Window, private val recordTouches: Boolean) : Window.Callback by inner {
        private val density = w.context.resources.displayMetrics.density

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val x = e.rawX / density.toDouble()
                val y = e.rawY / density.toDouble()
                if (recordTouches) c.tap(x, y)
                if (c.autocapture.cfg.enabled) {
                    val root = w.decorView
                    val chain = AutocaptureViews.chain(root, e.rawX, e.rawY)
                    c.autocapture.tap(chain, x, y)
                    if (AutocaptureViews.leafIsCompoundButton(root, e.rawX, e.rawY)) c.autocapture.change(chain)
                }
            }
            return inner.dispatchTouchEvent(e)
        }
    }
}
