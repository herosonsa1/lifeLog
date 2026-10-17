import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// version.properties 기반 버전 로드 및 APK 빌드 시 자동 버전 증가 (이전 빌드 파일 유지 보장)
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionPropsFile.inputStream().use { versionProps.load(it) }
}
var verMajor = versionProps.getProperty("VERSION_MAJOR", "1").toIntOrNull() ?: 1
var verMinor = versionProps.getProperty("VERSION_MINOR", "0").toIntOrNull() ?: 0
var verPatch = versionProps.getProperty("VERSION_PATCH", "0").toIntOrNull() ?: 0
var verCode = versionProps.getProperty("VERSION_CODE", "1").toIntOrNull() ?: 1

val requestedTasks = gradle.startParameter.taskNames.map { it.lowercase() }
val isApkBuildRequested = requestedTasks.any { taskName ->
    taskName.contains("assemble") ||
        taskName.contains("package") ||
        taskName.contains("install") ||
        taskName == "build" ||
        taskName.endsWith(":build")
}

if (isApkBuildRequested) {
    verPatch += 1
    verCode += 1
    // 루트(.\) 폴더에 동일 버전 파일이 이미 존재하면 덮어쓰지 않도록 미사용 버전까지 자동 증가
    while (
        File(rootProject.projectDir, "나의일상다이어리_v${verMajor}.${verMinor}.${verPatch}.apk").exists() ||
        File(rootProject.projectDir, "lifeLog_v${verMajor}.${verMinor}.${verPatch}.apk").exists()
    ) {
        verPatch += 1
        verCode += 1
    }
    versionProps.setProperty("VERSION_MAJOR", verMajor.toString())
    versionProps.setProperty("VERSION_MINOR", verMinor.toString())
    versionProps.setProperty("VERSION_PATCH", verPatch.toString())
    versionProps.setProperty("VERSION_CODE", verCode.toString())
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented on APK build")
    }
}

val currentVersionName = "${verMajor}.${verMinor}.${verPatch}"
val versionedApkFileName = "나의일상다이어리_v${currentVersionName}.apk"

android {
    namespace = "com.autologue.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autologue.app"
        minSdk = 26
        targetSdk = 34
        versionCode = verCode
        versionName = currentVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // 모든 빌드(Debug/Release) 시 최종 APK 파일명에 버전을 표시 (예: lifeLog_v1.0.1.apk)
    applicationVariants.all {
        outputs.all {
            val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl?.outputFileName = versionedApkFileName
        }
    }
}

tasks.matching { it.name.startsWith("package") || it.name.startsWith("assemble") }.configureEach {
    if (name.startsWith("package")) {
        val variant = name.removePrefix("package")
        dependsOn(tasks.matching { it.name == "write${variant}AppMetadata" })
    }
    doLast {
        // .\dist 폴더를 사용하지 않고 프로젝트 루트(.\) 폴더로 버전이 명시된 APK를 복사하며 이전 빌드 파일들을 그대로 유지
        val debugDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val legacyApk = File(debugDir, "app-debug.apk")
        if (legacyApk.exists()) {
            legacyApk.delete()
        }
        val builtApk = File(debugDir, versionedApkFileName)
        if (builtApk.exists()) {
            val targetRootApk = File(rootProject.projectDir, versionedApkFileName)
            builtApk.copyTo(targetRootApk, overwrite = true)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.google.truth:truth:1.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
