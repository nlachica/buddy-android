plugins {
    id("com.android.application")
}

// The same version as the phone app (read from its BuildInfo.java), so the pair always shows one version.
val buildInfo = file("../app/src/main/java/app/buddy/assistant/BuildInfo.java").readText()
val appVersionName = Regex("""VERSION = "([^"]+)"""").find(buildInfo)!!.groupValues[1]
val appVersionCode = Regex("""VERSION_CODE = (\d+)""").find(buildInfo)!!.groupValues[1].toInt()

android {
    namespace = "app.buddy.assistant.wear"
    compileSdk = 36

    defaultConfig {
        // The Wear Data Layer only connects apps with the same package name AND the same signing key as the phone's
        // Buddy, so this is the phone app's id (a watch and a phone are different devices, so they don't clash).
        applicationId = "app.buddy.assistant"
        minSdk = 30 // Wear OS 3, the Galaxy Watch4's floor
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Must be the SAME key as :app's (see applicationId above). Without the variables the APK is unsigned.
    val keystorePath = System.getenv("BUDDY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BUDDY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUDDY_KEY_ALIAS") ?: "buddy"
                keyPassword = System.getenv("BUDDY_KEY_PASSWORD") ?: System.getenv("BUDDY_KEYSTORE_PASSWORD")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // The Wear Data Layer (talks to the phone's Buddy)
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
