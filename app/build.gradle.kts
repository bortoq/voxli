plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voxli"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voxli"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ─── Verification task ─────────────────────────────────────────────
// Runs verify_contract.py after assembleDebug to ensure roadmap compliance.
tasks.register<Exec>("verifyContract") {
    dependsOn("assembleDebug")
    workingDir = rootProject.projectDir
    commandLine("python3", "scripts/verify_contract.py", "--apk", 
        "${projectDir}/build/outputs/apk/debug/app-debug.apk")
    isIgnoreExitValue = false
    doFirst {
        val reportFile = file("${projectDir}/build/reports/verify-contract.txt")
        reportFile.parentFile.mkdirs()
        standardOutput = reportFile.outputStream()
    }
    doLast {
        println("📄 Verification report: ${projectDir}/build/reports/verify-contract.txt")
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("verifyContract")
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Koin
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Network
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.okhttp.logging)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // Coil
    implementation(libs.coil.compose)
}
