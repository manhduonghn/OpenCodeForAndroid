plugins {
    kotlin("multiplatform")
}

kotlin {
    // The bridge is shipped as an executable for the two ABIs that have an official OpenCode
    // build. It is started from $PREFIX/bin/pkg and $PREFIX/bin/oc-install, which are symlinks to
    // $PREFIX/libexec/oc-bridge.
    androidNativeArm64 {
        binaries {
            executable {
                baseName = "oc-bridge"
                entryPoint = "com.opencode.android.bridge.main"
                // Be explicit: an Android `executable` used to be a NativeActivity shared
                // library. "standalone" produces a real ELF program with the bionic loader,
                // which is what the pkg/oc-install symlinks exec.
                binaryOptions["androidProgramType"] = "standalone"
            }
        }
    }
    androidNativeX64 {
        binaries {
            executable {
                baseName = "oc-bridge"
                entryPoint = "com.opencode.android.bridge.main"
                binaryOptions["androidProgramType"] = "standalone"
            }
        }
    }

    // The source lives in src/nativeMain/kotlin. The default hierarchy template creates the
    // nativeMain source set for native targets and points it at that directory, so both ABIs
    // compile the same file without a second, manually managed source set.
    applyDefaultHierarchyTemplate()
}
