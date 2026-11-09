// Same plugin versions as sdks/android (Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2 wrapper).
// The publish plugin's own dependencies would otherwise lift AGP and the Kotlin plugin on the build
// classpath (Gradle takes the highest version), which needs a newer Gradle than the wrapper pins.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("com.android.tools.build:gradle:8.7.3")
            eachDependency { if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21") }
        }
    }
}
plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
