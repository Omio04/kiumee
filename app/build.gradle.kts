// 조립점 — 소유: P1. 로직 없이 어댑터를 골라 끼우기만 한다 (설계서 16.8).
import java.util.Properties

plugins { alias(libs.plugins.android.application) }

// 서버 주소 · 앱 키는 코드에 쓰지 않는다. 저장소 밖 파일(local.properties, git에 안 올라감)이나 ~/.gradle/gradle.properties 에 둔다:
//   kl.serverUrl=https://내-서버.example.com
//   kl.appKey=서버의 APP_KEY 와 같은 값
// (LLM 키는 서버에만 있다. 앱에는 앱 키만 들어간다.)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String): String = (localProps.getProperty(name) ?: providers.gradleProperty(name).orNull ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "kr.kiosklens.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "kr.kiosklens.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "SERVER_URL", "\"${prop("kl.serverUrl")}\"")
        buildConfigField("String", "APP_KEY", "\"${prop("kl.appKey")}\"")
    }
    buildFeatures { buildConfig = true }

    // real: 실구현 / mock: 모든 포트에 Fake 바인딩 (설계서 16.8)
    flavorDimensions += "impl"
    productFlavors {
        create("real") {
            dimension = "impl"
            buildConfigField("boolean", "MOCK", "false")
        }
        create("mock") {
            dimension = "impl"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
            buildConfigField("boolean", "MOCK", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":vision"))
    implementation(project(":voice"))
    implementation(project(":agent"))
    implementation(project(":overlay"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
}
