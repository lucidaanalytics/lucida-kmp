import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("com.vanniktech.maven.publish")
    kotlin("multiplatform")
}

// Like sdks/android, the Android target needs an Android SDK; without one the JVM + Apple targets still build and test.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Library/Android/sdk".takeIf { file(it).isDirectory }
val withAndroid = androidSdk != null
if (withAndroid) apply(plugin = "com.android.library")

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // java.* based actuals shared by the JVM and Android targets (locks, scheduler, HttpURLConnection).
            group("jvmCommon") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes") // internal expect class Lock
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (withAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
            languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
        }
    }
}

if (withAndroid) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
        namespace = "com.lucida.kmp"
        compileSdk = 34
        defaultConfig { minSdk = 23 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

// The iOS simulator test binary reads the shared conformance vectors from the repo's testdata dir.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_LUCIDA_TESTDATA", System.getenv("LUCIDA_TESTDATA") ?: rootDir.resolve("../../testdata").canonicalPath)
}

mavenPublishing {
    coordinates("io.github.lucidaanalytics", "lucida-kmp", "0.1.0")
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    pom {
        name.set("lucida-kmp")
        description.set("Lucida Kotlin Multiplatform SDK (JVM, Android, iOS)")
    }
}
