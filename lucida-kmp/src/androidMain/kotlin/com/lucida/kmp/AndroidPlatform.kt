package com.lucida.kmp

import android.app.Application
import android.content.Context

private var appContext: Context? = null

/** Call once (Application.onCreate) before [platformDefaults] / [Lucida.create]. */
fun attachAndroid(app: Context) {
    appContext = app.applicationContext
}

/** [KeyValueStore] over SharedPreferences (`apply()`: async disk write, instant in-memory read). */
class SharedPrefsStore(context: Context, name: String = "lucida") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()

    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

@Suppress("DEPRECATION")
actual fun platformDefaults(requestTimeoutMs: Int): Platform {
    val app = checkNotNull(appContext) { "call attachAndroid(application) before platformDefaults()/Lucida.create()" }
    val info = app.packageManager.getPackageInfo(app.packageName, 0)
    return Platform(
        SharedPrefsStore(app),
        UrlConnectionHttp(requestTimeoutMs),
        DeviceInfo(
            os = "Android",
            osVersion = android.os.Build.VERSION.RELEASE ?: "unknown",
            model = android.os.Build.MODEL ?: "unknown",
            manufacturer = android.os.Build.MANUFACTURER,
            appVersion = info.versionName ?: "unknown",
            appBuild = (if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()).toString(),
        ),
    )
}

/** Wi-Fi/Ethernet check for `LucidaOptions.networkAllowed` (Wi-Fi-only data mode). */
fun onUnmeteredLink(app: Application): Boolean {
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
}
