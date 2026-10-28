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
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
}
