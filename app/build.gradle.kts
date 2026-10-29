plugins {
    id("com.android.application") version "9.3.1"
    id("org.jetbrains.kotlin.android") version "2.4.10"
}

android {
    namespace = "com.opencode.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 26
        // Termux packages are executed directly from the app-private prefix. Android 10+ forbids
        // execve() of app data files for apps that target API 29 or higher (the same reason the
        // Termux app itself still targets API 28), so stay on 28.
        targetSdk = 28
        versionCode = 9
        versionName = "1.0.8"
    }

    signingConfigs {
        create("release") {
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
            // Both build types are signed with the same key when one is provided, otherwise
            // Android refuses to install an update over the app that is already on the device.
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The app is written in Kotlin only, the sources live in src/main/kotlin.
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // lintVitalAnalyzeRelease crashes inside lint's own JavaDoc parser
        // (NoSuchMethodError in JavaDocParser.parseDataItem while analyzing),
        // an AGP/lint bug, not a lint finding. Skip the release lint gate until
        // the toolchain bug is fixed.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
}
