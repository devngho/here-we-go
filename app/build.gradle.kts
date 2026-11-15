import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 추천 서버(herewego-server) 주소와 앱 키. 우선순위: -P 옵션 > local.properties > 기본값.
//   herewego.serverUrl=http://10.0.2.2:8080   (로컬 서버. 기본값은 데모 서버)
//   herewego.appKey=...                         (서버에 APP_KEY 를 설정했을 때만)
// 서버에 닿지 않으면 앱은 내장 샘플 데이터로 동작한다.
//
// release 서명: local.properties 에 herewego.release.storeFile / storePassword / keyAlias / keyPassword 를
// 적으면 그 키로, 없으면 디버그 키로 서명해 바로 설치할 수 있게 한다.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProperty(name: String, default: String = ""): String =
    (providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: default).trim()

val demoServerUrl = "https://pleos-demo.ngho.dev"

android {
    namespace = "io.github.devngho.herewego"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.devngho.herewego"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SERVER_URL", "\"${localProperty("herewego.serverUrl", demoServerUrl)}\"")
        buildConfigField("String", "SERVER_APP_KEY", "\"${localProperty("herewego.appKey")}\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperty("herewego.release.storeFile")
            if (storeFilePath.isNotEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = localProperty("herewego.release.storePassword")
                keyAlias = localProperty("herewego.release.keyAlias")
                keyPassword = localProperty("herewego.release.keyPassword")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // CarUxRestrictionsManager (주차 여부 확인). 차량 OS 에만 있으므로 필수 라이브러리는 아님.
    useLibrary("android.car", false)
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
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 추천 서버 호출
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Pleos Connect SDK
    implementation(libs.pleos.navi.helper)
    implementation(libs.pleos.tts)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
