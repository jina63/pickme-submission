plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services) apply false
}

// Opt-in for the independent local invitation prototype; normal builds keep Firebase.
val localInvitationDemo = providers.gradleProperty("pickmeLocalInvites").orNull == "true"
if (!localInvitationDemo) {
    apply(plugin = "com.google.gms.google-services")
}

val targetAndroidSdk = 35
val appVersionMajor = 1
val appVersionMinor = 0
val appVersionPatch = 0

android {
    namespace = "com.pickme.car"
    compileSdk = targetAndroidSdk

    defaultConfig {
        applicationId = "com.pickme.car"
        minSdk = 23
        targetSdk = targetAndroidSdk
        versionCode =
            targetAndroidSdk * 1_000_000 +
                appVersionMajor * 10_000 +
                appVersionMinor * 100 +
                appVersionPatch
        versionName =
            "$appVersionMajor.$appVersionMinor.$appVersionPatch-sdk$targetAndroidSdk"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("com.google.zxing:core:3.5.3") // Generate invitation QR locally; no camera or external QR service.

    // Firebase Realtime Database (딱픽업 주문·매장)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
