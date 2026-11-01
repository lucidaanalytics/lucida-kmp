// Same plugin versions as sdks/android (Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2 wrapper).
plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
