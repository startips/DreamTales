import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        FileInputStream(localFile).use { load(it) }
    }
}

fun getPropOrEnv(key: String, defaultVal: String = ""): String {
    return localProperties.getProperty(key)
        ?: System.getenv(key)
        ?: defaultVal
}

val releaseKeystorePath = getPropOrEnv("RELEASE_KEYSTORE_PATH")
val releaseKeystoreFile = releaseKeystorePath
    .takeIf { it.isNotBlank() }
    ?.let { file(it) }
val hasReleaseKeystore = releaseKeystoreFile?.exists() == true
val releaseStorePassword = getPropOrEnv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = getPropOrEnv("RELEASE_KEY_ALIAS", "androiddebugkey")
val releaseKeyPassword = getPropOrEnv("RELEASE_KEY_PASSWORD", releaseStorePassword)

require(!hasReleaseKeystore || releaseStorePassword.isNotBlank()) {
    "RELEASE_KEYSTORE_PATH 已配置，但缺少 RELEASE_KEYSTORE_PASSWORD"
}

android {
    namespace = "com.bedtime.dreamtales"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bedtime.dreamtales"
        minSdk = 34
        targetSdk = 34
        versionCode = 20
        versionName = "2.3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "DEFAULT_MIMO_API_KEY", "\"${getPropOrEnv("DEFAULT_MIMO_API_KEY")}\"")
        buildConfigField("String", "DEFAULT_CPA_BASE_URL", "\"${getPropOrEnv("DEFAULT_CPA_BASE_URL", "https://api.openai.com/v1/")}\"")
        buildConfigField("String", "DEFAULT_CPA_API_KEY", "\"${getPropOrEnv("DEFAULT_CPA_API_KEY")}\"")
        buildConfigField("String", "DEFAULT_CPA_MODEL", "\"${getPropOrEnv("DEFAULT_CPA_MODEL", "gemini-3.5-flash-lite")}\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // 本地没有发布密钥时仍可构建，便于开发调试
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
        dex {
            // minSdk 34 默认会把 dex 以未压缩形式打包，导致 APK 从 ~17MB 膨胀到 ~52MB；
            // 强制压缩 dex，保持下载体积和旧版本一致，安装时由系统解压。
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Media3
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Retrofit & OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Security EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
