# Lucida Kotlin Multiplatform SDK

One Kotlin codebase for Android, iOS (arm64, x64, simulator) and the JVM. The client, event queue, flags, consent gate and
mobile wireframe replay live in `commonMain` and mirror `sdks/android` (same wire format, same behaviour, same shared
conformance vectors in `testdata/flags`). Artifact package: `com.lucida.kmp`.

| Layer | What | Where |
| --- | --- | --- |
| Client | capture, identify, alias, group, reset, screen, super properties, person profiles, deep links, `beforeSend` hooks | `commonMain` (`Core`, `Lucida`) |
| Queue | batching (`flushAt`, interval), gzip POST `/v1/batch`, retry with jittered backoff, drop-oldest, persisted until acked, optional `QueueCipher` | `EventQueue` |
| Consent | `OPT_IN` / `OPT_OUT`; a `GatedStore` keeps identity, session and props in memory until analytics consent, so nothing is written before consent | `Consent` |
| Flags | local evaluation of bootstrap definitions (spec v1 + v2 ops), server decide (`POST /v1/flags`), cached last response, `$feature_flag_called`, `$feature/<key>` enrichment | `Flags`, `FlagEval` |
| Remote config | `GET /v1/config` tightens replay sample rate, kill switch, min duration | `ReplayRecorder.loadRemote` |
| Replay | rrweb mobile wireframe (Meta, FullSnapshot, taps), seq per window, gzip POST `/v1/replay` | `Wireframe`, `ReplayRecorder` |
| Autocapture | `$autocapture` taps/changes and `$rageclick` (off by default; input text never read, `lc-no-capture` skips a subtree) | `Autocapture` + `AutocaptureViews` (Android) / `UIKitAutocapture` (iOS) |
| Guides | `GET /v1/guides`, modal/banner/announcement/slideout/tour; url (screen), flag, sample, devices, seen targeting (other keys skip the guide); native alert presenter | `GuidesService` + `AlertGuidePresenter` |
| Screenshot replay | `WireframeMasking(mode = ReplayMode.SCREENSHOT)`: the window is drawn, the mask plan painted, then JPEG encoded on device | `ScreenshotCapture` (Android) / `UIKitScreenshot` (iOS) |
| Queue ciphers | `KeystoreQueueCipher` (Android Keystore AES-256-GCM), `KeychainQueueCipher` (Keychain key, CommonCrypto AES-256-CBC + HMAC-SHA256) | `LucidaOptions.queueCipher` |
| Ports (expect/actual) | `Lock`, clock, `Scheduler`, `platformDefaults()` = storage + HTTP + device info | `Platform.kt` and the platform source sets |

Platform actuals: Android `SharedPreferences` + `HttpURLConnection` (+ `Window.Callback` tap hook, View-tree walker); iOS
`NSUserDefaults` + `NSURLSession` + GCD (+ UIView walker, tap recognizer, lifecycle notifications); JVM JSON file store
(`$LUCIDA_HOME` or `~/.lucida`) + `HttpURLConnection`. The JVM target has no view tree, so replay there is a no-op unless you
hand `Lucida.captureScreen` a `ViewNode` tree yourself.

Not ported: Compose markers (use `sdks/android`'s `lucida-compose`). Network and log capture are Watchtower's (see
`docs/seam/watchtower.md`): requests to `tracingHeaders` hosts carry `baggage: lucida.session_id=<id>` so Watchtower links its
spans and logs to the session. Apple uploads use a gzip container with stored (uncompressed) blocks; the server only needs the
container.

## Install

```kotlin
// settings.gradle.kts / build.gradle.kts of your KMP module (publishing coordinates are set when the artifact is released)
kotlin {
    sourceSets.commonMain.dependencies { implementation(project(":lucida-kmp")) }
}
```

## Usage

```kotlin
// common code
val lucida = Lucida.create(
    apiKey = "lc_pk_...",
    apiHost = "https://ingest.example.com",
    options = LucidaOptions(consentMode = ConsentMode.OPT_IN),
)
lucida.setConsent(mapOf("analytics" to true, "replay" to true)) // nothing is persisted or sent before this in OPT_IN mode
lucida.identify("user-42", set = mapOf("plan" to "pro"))
lucida.capture("checkout_started", mapOf("items" to 3))
lucida.screen("Checkout")
if (lucida.flags.isEnabled("new-nav")) { /* ... */ }
lucida.reset()
```

Android:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LucidaAndroid.init(this, "lc_pk_...", "https://ingest.example.com", replayMasking = WireframeMasking())
    }
}
```

`replayMasking = null` (default) never walks views. With a value, each resumed Activity records `$screen` plus a wireframe
and taps. Text, inputs and images are masked by default, password inputs always. Tag views with `view.tag = LcTag.BLOCK`
(`lc-block`, `lc-ignore`, `lc-mask`, `lc-unmask`).

iOS (Swift: `LucidaIos.shared.doInit(...)`):

```kotlin
LucidaIos.init("lc_pk_...", "https://ingest.example.com", replayMasking = WireframeMasking())
LucidaIos.installTapRecorder()
LucidaIos.screen("Checkout") // from viewDidAppear
```

Set `accessibilityIdentifier = "lc-block" | "lc-ignore" | "lc-mask" | "lc-unmask"` (or the Swift SDK's `lucida-sensitive` /
`lucida-unmask`) to control masking. Secure text fields are never read.

Replay needs `analytics` and `replay` consent. Sampling is per session, `ReplayOptions.throttleMs` spaces captures, a screen
is re-captured only when the throttle allows or the session rotated, and chunks (`seq` per window) go out with the queue flush.

## Fallbacks and flag details

`LucidaOptions(flags = FlagOptions(fallbacks = mapOf("new_nav" to false)))` serves that value, reason `fallback`, when a flag has no
value (missing, failed load, cold start); fallback reads emit no `$feature_flag_called`. `lucida.flags.getFeatureFlagDetails("key")`
returns `FlagDetails` (value, payload, variant, raw `reason`, `conditionIndex`, `requestId`, `evaluatedLocally`), or null for an
unknown key without a fallback. `lucida.flags.onFlagsStatus { CHANGED | STALE | ERROR }` reports reloads.

## Test

```
cd sdks/kmp
./gradlew :lucida-kmp:jvmTest                      # commonTest + jvmTest on the JVM (no Android SDK or Xcode needed)
./gradlew :lucida-kmp:testDebugUnitTest            # commonTest on Android (needs ANDROID_HOME)
./gradlew :lucida-kmp:iosSimulatorArm64Test        # commonTest on an iOS simulator (macOS + Xcode)
./gradlew :lucida-kmp:compileKotlinIosArm64 :lucida-kmp:compileDebugKotlinAndroid
```

The flag vectors under `testdata/flags` are read through `readTestdata` (JVM: walks up to `testdata/`; iOS: the Gradle task
passes `LUCIDA_TESTDATA`).

---

This repository is a read-only mirror of the Lucida monorepo (synced by a script, never edited by hand); releases and provenance attestations are built here by GitHub Actions. Issues are welcome, changes land upstream.
