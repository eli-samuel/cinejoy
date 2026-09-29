plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions run number, so every CI build is numbered higher than the one before it.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.cinejoytv.app"
    compileSdk = 34

    defaultConfig {
        minSdk = 22 // Fire OS 5 (Fire TV Stick 1st/2nd gen) and newer
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.2.$buildNumber"
    }

    buildFeatures {
        buildConfig = true
    }

    // One app per site. Each flavor has its own app ID, name, icon and banner (src/<flavor>/res),
    // so the apps install side by side.
    flavorDimensions += "site"
    productFlavors {
        create("cinejoy") {
            dimension = "site"
            applicationId = "com.cinejoytv.app"
            buildConfigField("String", "HOME_URL", "\"https://cinejoy.pk/\"")
            // Off-site top-level navigations allowed besides the site itself (sign-in providers).
            buildConfigField("String[]", "NAV_ALLOWLIST", "{\"google.com\", \"facebook.com\", \"apple.com\"}")
        }
        create("nhl") {
            dimension = "site"
            applicationId = "com.firetvapps.nhltv"
            buildConfigField("String", "HOME_URL", "\"https://nhlstreams.io/\"")
            buildConfigField("String[]", "NAV_ALLOWLIST", "{}")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK can be sideloaded directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
