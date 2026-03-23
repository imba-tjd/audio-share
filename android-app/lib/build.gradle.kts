plugins {
    id("com.android.library")
}

android {
    namespace = "ashipo.jopus"

    compileSdk = 36

    defaultConfig {

        minSdk = 31

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    testBuildType = "release"

    buildTypes {
        release {
            isDefault = true

            externalNativeBuild {
                cmake {
//                    cppFlags += "-flto"
                }
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}
