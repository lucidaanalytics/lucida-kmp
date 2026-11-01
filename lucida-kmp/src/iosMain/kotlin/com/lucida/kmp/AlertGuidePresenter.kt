package com.lucida.kmp

import platform.Foundation.NSURL
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Guide steps as a UIAlertController on the top-most view controller of [window], always on the main thread.
 * Text is set as plain strings, never markup. Every button is a default action (an alert allows one cancel action only).
 */
class AlertGuidePresenter(private val window: () -> UIWindow?) : GuidePresenter {
    override fun present(guide: MobileGuide, step: Int, buttons: List<GuideButton>, onAction: (GuideButton?) -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            var top: UIViewController? = window()?.rootViewController
            while (top?.presentedViewController != null) top = top.presentedViewController
            val host = top ?: return@dispatch_async onAction(null)
            val s = guide.steps[step]
            val alert = UIAlertController.alertControllerWithTitle(s.title ?: guide.name, s.body, UIAlertControllerStyleAlert)
            buttons.forEach { b -> alert.addAction(UIAlertAction.actionWithTitle(b.label, UIAlertActionStyleDefault) { _ -> onAction(b) }) }
            host.presentViewController(alert, animated = true, completion = null)
        }
    }

    override fun open(url: String) {
        dispatch_async(dispatch_get_main_queue()) {
            NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any?>(), completionHandler = null) }
        }
    }
}
