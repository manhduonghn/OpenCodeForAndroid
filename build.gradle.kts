// Plugins are declared once here and applied by each module without a version. Declaring the
// Kotlin plugin separately in :app and :cli-bridge loads the Kotlin Gradle plugin in two class
// loaders, so both projects end up owning a different instance of the K/N bundle build service and
// task creation fails with "Cannot set ... kotlinNativeBundleBuildService".
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
