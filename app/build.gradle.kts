plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.creepypasta.earpiece"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.creepypasta.earpiece"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // R8 on: shrinking and obfuscation are safe here because the app has no
            // reflective lookups and no third-party SDKs (see app/proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 8 turns BuildConfig generation off by default; the about footer reads
        // BuildConfig.VERSION_NAME, so opt back in explicitly.
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Kotlin 2.x style: the compiler options live in the top-level `kotlin` extension
// (the AGP `kotlinOptions` block is deprecated). Kept in sync with compileOptions
// above so Java and Kotlin both target the JVM 17 bytecode level.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
