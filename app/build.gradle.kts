plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// pkg is a symlink to the Kotlin/Native executable. The module is built first and
// its two Android binaries are packaged as assets, so the APK contains no shell launcher at all.
val cliBridgeAssets = layout.buildDirectory.dir("generated/cliBridgeAssets")
val arm64BridgeDir = rootProject.file("cli-bridge/build/bin/androidNativeArm64/releaseExecutable")
val x64BridgeDir = rootProject.file("cli-bridge/build/bin/androidNativeX64/releaseExecutable")
val prepareCliBridgeAssets = tasks.register<Sync>("prepareCliBridgeAssets") {
    dependsOn(":cli-bridge:linkReleaseExecutableAndroidNativeArm64")
    dependsOn(":cli-bridge:linkReleaseExecutableAndroidNativeX64")
    into(cliBridgeAssets)
    // Copy the script-level paths into local values. A task action that reads a top-level property
    // captures the whole build script, which the configuration cache refuses to serialize.
    val arm64Dir = arm64BridgeDir
    val x64Dir = x64BridgeDir
    from(arm64Dir) {
        // .kexe on Linux/macOS hosts, .exe on Windows hosts.
        include("oc-bridge.kexe", "oc-bridge.exe")
        rename { "oc-bridge" }
        into("cli/arm64-v8a")
    }
    from(x64Dir) {
        include("oc-bridge.kexe", "oc-bridge.exe")
        rename { "oc-bridge" }
        into("cli/x86_64")
    }
    doFirst {
        listOf("arm64-v8a" to arm64Dir, "x86_64" to x64Dir).forEach { (abi, dir) ->
            val binary = File(dir, "oc-bridge.kexe").takeIf { it.isFile }
                ?: File(dir, "oc-bridge.exe")
            check(binary.isFile) { "The $abi oc-bridge executable was not built in $dir" }
        }
    }
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
        versionCode = 13
        versionName = "1.0.12"
    }

    signingConfigs {
        getByName("debug") {
            // A fixed debug key is committed so every build (local and CI) has the same
            // signature. Otherwise each CI run generates a new debug keystore and the APK
            // has to be uninstalled before it can be installed over the previous one.
            // This key is public and only used for debug builds.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
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
            assets.srcDir(cliBridgeAssets.get().asFile)
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
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    // Material 3 (Material You) for the native screens, including dynamic color on Android 12+.
    implementation("com.google.android.material:material:1.14.0")
    // Document-start script: themes the OpenCode web UI before it paints, not after.
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
}

// The asset merge tasks read the generated directory, make sure the two native binaries exist
// before any variant starts. preBuild is kept as an anchor so a task that does not merge assets
// still builds them.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareCliBridgeAssets)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareCliBridgeAssets)
}

// The Java sources are gone, but AGP still compares both toolchains: the Kotlin JVM target has to
// match compileOptions above or the build fails with "Inconsistent JVM Target Compatibility".
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
