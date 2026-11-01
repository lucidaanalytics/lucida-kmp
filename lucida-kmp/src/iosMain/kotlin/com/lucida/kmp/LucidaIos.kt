package com.lucida.kmp

import kotlinx.cinterop.useContents
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.roundToInt

/**
 * iOS entry point: `LucidaIos.init("lc_pk_...", "https://ingest.example.com")` wires the common client to NSUserDefaults,
 * NSURLSession and the app lifecycle notifications (`$app_*` events, flush on background). Call [screen] when a screen
 * appears: it records `$screen` and, when replay is enabled, a wireframe of the key window. Taps are recorded by
 * [installTapRecorder] or by calling [tap] yourself.
 */
object LucidaIos {
    lateinit var client: Lucida
        private set

    private var masking: WireframeMasking? = null
    private var tapTarget: TapTarget? = null

    fun init(apiKey: String, apiHost: String, options: LucidaOptions = LucidaOptions(), replayMasking: WireframeMasking? = null): Lucida {
        if (options.guides.presenter == null) options.guides.presenter = AlertGuidePresenter { keyWindow() }
        val c = Lucida.create(apiKey, apiHost, options)
        client = c
        masking = replayMasking
        val nc = NSNotificationCenter.defaultCenter
        nc.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { c.backgrounded() }
        nc.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { c.foregrounded() }
        return c
    }

    private fun keyWindow(): UIWindow? =
        UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
            .flatMap { s -> s.windows.filterIsInstance<UIWindow>() }.firstOrNull { it.keyWindow }

    /** Record `$screen` and (replay enabled + consented) the key window's wireframe. Hops to the main thread. */
    fun screen(name: String) {
        dispatch_async(dispatch_get_main_queue()) {
            val m = masking
            val w = keyWindow()
            if (m == null || w == null) {
                client.screen(name)
                return@dispatch_async
            }
            val (width, height) = w.bounds.useContents { size.width.roundToInt() to size.height.roundToInt() }
            val regions = MarkerRegistry.snapshot()
            val shot = if (m.mode == ReplayMode.SCREENSHOT) {
                { tree: ViewNode -> UIKitScreenshot.capture(w, ScreenshotMasks.plan(tree, regions), m.screenshotQuality) }
            } else {
                null
            }
            client.captureScreen(name, width, height, shot) { UIKitViewHierarchy(m).capture(w, regions) }
        }
    }

    /** A tap in points, window coordinates. */
    fun tap(x: Double, y: Double) = client.tap(x, y)

    /**
     * Adds a pass-through tap recognizer to the key window so taps land in the replay and, with `options.autocapture`
     * enabled, as `$autocapture` events (no effect on other touches).
     */
    fun installTapRecorder() {
        dispatch_async(dispatch_get_main_queue()) {
            val w = keyWindow() ?: return@dispatch_async
            if (tapTarget != null) return@dispatch_async
            val t = TapTarget(client).also { tapTarget = it }
            val r = UITapGestureRecognizer(target = t, action = NSSelectorFromString("tapped:"))
            r.cancelsTouchesInView = false
            r.delegate = t
            w.addGestureRecognizer(r)
        }
    }

    private class TapTarget(private val c: Lucida) : NSObject(), UIGestureRecognizerDelegateProtocol {
        @kotlinx.cinterop.ObjCAction
        fun tapped(r: UITapGestureRecognizer) {
            val w = r.view ?: return
            r.locationInView(null).useContents {
                c.tap(x, y) // no-op until a replay snapshot exists
                if (c.autocapture.cfg.enabled) {
                    val chain = UIKitAutocapture.chain(w, x, y)
                    c.autocapture.tap(chain, x, y)
                    if (UIKitAutocapture.leafIsToggle(w, x, y)) c.autocapture.change(chain)
                }
            }
        }

        override fun gestureRecognizer(gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer) = true
    }
}
