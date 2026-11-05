import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// 모든 빌드 산출물(APK, AAB 등) 통합 저장 경로: .\dist
val distDir = rootProject.file("dist").apply { mkdirs() }

// version.properties 기반 버전 로드 및 APK/AAB 빌드 시 자동 버전 증가 (이전 빌드 파일 유지 보장)
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
val isDistBuildRequested = requestedTasks.any { taskName ->
    taskName.contains("assemble") ||
        taskName.contains("package") ||
        taskName.contains("bundle") ||
        taskName.contains("install") ||
        taskName == "build" ||
        taskName.endsWith(":build")
}

if (isDistBuildRequested) {
    verPatch += 1
    verCode += 1
    // .\dist 폴더(및 루트)에 동일 버전 산출물이 이미 존재하면 덮어쓰지 않도록 미사용 버전까지 자동 증가
    while (
        File(distDir, "나의일상다이어리_v${verMajor}.${verMinor}.${verPatch}.apk").exists() ||
        File(distDir, "나의일상다이어리_v${verMajor}.${verMinor}.${verPatch}.aab").exists() ||
        File(distDir, "lifeLog_v${verMajor}.${verMinor}.${verPatch}.apk").exists() ||
        File(rootProject.projectDir, "나의일상다이어리_v${verMajor}.${verMinor}.${verPatch}.apk").exists()
    ) {
        verPatch += 1
        verCode += 1
    }
    versionProps.setProperty("VERSION_MAJOR", verMajor.toString())
    versionProps.setProperty("VERSION_MINOR", verMinor.toString())
    versionProps.setProperty("VERSION_PATCH", verPatch.toString())
    versionProps.setProperty("VERSION_CODE", verCode.toString())
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented on APK/AAB build (saved to dist)")
    }
}

val currentVersionName = "${verMajor}.${verMinor}.${verPatch}"
val versionedApkFileName = "나의일상다이어리_v${currentVersionName}.apk"
val versionedReleaseApkFileName = "나의일상다이어리_v${currentVersionName}_release.apk"
val versionedAabFileName = "나의일상다이어리_v${currentVersionName}.aab"

// 로컬 업로드 키스토어(keystore.properties) 로드 — 미존재 시 debug 서명으로 안전 폴백
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    val rawText = keystorePropsFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
    keystoreProps.load(rawText.reader())
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasValidReleaseKeystore = releaseStoreFile != null && releaseStoreFile.exists()

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

    signingConfigs {
        if (hasValidReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasValidReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    // 모든 빌드(Debug/Release) 시 최종 APK 파일명에 버전을 표시
    applicationVariants.all {
        val isReleaseVariant = buildType.name == "release"
        outputs.all {
            val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl?.outputFileName = if (isReleaseVariant) {
                versionedReleaseApkFileName
            } else {
                versionedApkFileName
            }
        }
    }
}

// APK 빌드 완료 시 .\dist 폴더로 자동 복사
tasks.matching { it.name.startsWith("package") || it.name.startsWith("assemble") }.configureEach {
    if (name.startsWith("package")) {
        val variant = name.removePrefix("package")
        dependsOn(tasks.matching { it.name == "write${variant}AppMetadata" })
    }
    doLast {
        distDir.mkdirs()
        val debugDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val legacyApk = File(debugDir, "app-debug.apk")
        if (legacyApk.exists()) {
            legacyApk.delete()
        }
        val builtDebugApk = File(debugDir, versionedApkFileName)
        if (builtDebugApk.exists()) {
            builtDebugApk.copyTo(File(distDir, versionedApkFileName), overwrite = true)
        }

        val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val builtReleaseApk = File(releaseDir, versionedReleaseApkFileName)
        if (builtReleaseApk.exists()) {
            builtReleaseApk.copyTo(File(distDir, versionedReleaseApkFileName), overwrite = true)
        }
    }
}

// AAB(Android App Bundle) 빌드 완료 시 .\dist 폴더로 자동 복사
tasks.matching { it.name.startsWith("bundle") || it.name.startsWith("sign") && it.name.endsWith("Bundle") }.configureEach {
    doLast {
        distDir.mkdirs()
        val bundleRoot = layout.buildDirectory.dir("outputs/bundle").get().asFile
        if (bundleRoot.exists()) {
            val aabCandidates = bundleRoot.walkTopDown().filter { it.isFile && it.extension == "aab" }.toList()
            val preferredAab = aabCandidates.firstOrNull { it.invariantSeparatorsPath.contains("/release/") }
                ?: aabCandidates.firstOrNull()
            if (preferredAab != null && preferredAab.exists()) {
                preferredAab.copyTo(File(distDir, versionedAabFileName), overwrite = true)
            }
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
