import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 署名設定: keystore.properties があれば release 鍵で署名する (リポジトリには含めない)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.giracle.android"
    compileSdk = 37

    defaultConfig {
        // 持ち主 (GitHub: 1an28) の名前空間にする (D-015)。各部分は英字で始める決まりがあるので先頭に u を付ける。
        // namespace (コードのパッケージ名) は変えない。利用者には見えず、変えると差分が大きくなるだけなので。
        applicationId = "io.github.u1an28.giracle.kotlin"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "0.8.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        // Baseline Profile の記録用 (#8、D-021)。配布版と同じだが難読化しない (記録したクラス名がソースと同じになる)
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // 同梱するライブラリの翻訳を日本語と英語だけにして APK を小さくする
    androidResources {
        localeFilters += listOf("ja", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
}

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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.unifiedpush.connector)
    // 配信アプリが無い端末でも、Google Play 開発者サービス経由でサーバーの Web Push を受け取る (D-019)
    implementation(libs.unifiedpush.embedded.fcm)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric は実行時に Android 本体の jar を自前でダウンロードするが、プロキシ環境では失敗するため、
// Gradle で取得してオフラインモードで使う。
val robolectricRuntime: Configuration = configurations.create("robolectricRuntime")
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}
val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")
val copyRobolectricDeps = tasks.register<Copy>("copyRobolectricDeps") {
    from(robolectricRuntime)
    into(robolectricDepsDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDepsDir.get().asFile.absolutePath)
    // 実サーバー結合テスト用の環境変数を渡す
    listOf("GIRACLE_TEST_SERVER", "GIRACLE_TEST_ADMIN_USER", "GIRACLE_TEST_ADMIN_PASS").forEach { k ->
        System.getenv(k)?.let { environment(k, it) }
    }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
