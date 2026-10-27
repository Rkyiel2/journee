plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.journee.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.journee.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // A fixed key, committed in the repo, so every new APK installs over the previous one
    // (Android refuses an update signed with a different key, and you would lose your login).
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/journee.jks")
            storePassword = "journee"
            keyAlias = "journee"
            keyPassword = "journee"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
        checkReleaseBuilds = false
    }
}

// The Pronote engine (pawnote) is bundled by `npm run build` in js/. Fail early with a clear message.
val checkEngine by tasks.registering {
    val engine = file("src/main/assets/engine.js")
    doLast {
        if (!engine.exists()) {
            throw GradleException("app/src/main/assets/engine.js is missing. Run: cd js && npm install && npm run build")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkEngine) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
}
