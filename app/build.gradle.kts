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
        // 개발자 확인용 인식 표시 (글자 네모 · 위쪽 상태 줄). 평소엔 끔: local.properties 에 kl.showRecognition=true
        // 계속 듣기(도움 모드에서 답이 끝나면 버튼 없이 다시 듣기). 기본은 끔: 어르신이 "말하기"를 눌러 다시 질문한다
        buildConfigField("boolean", "HANDS_FREE", (prop("kl.handsFree") == "true").toString())
        // 기본은 주문 흐름 안내: 안정된 새 화면이 보이면 추가 질문 없이 다음 단계를 알려 준다.
        // 물어볼 때만 답하는 도움 모드가 필요할 때에만 kl.helpMode=true 로 켠다.
        buildConfigField("boolean", "HELP_MODE", (prop("kl.helpMode") == "true").toString())
        buildConfigField("boolean", "SEND_IMAGE", (prop("kl.sendImage") != "false").toString())   // 질문 때 카메라 사진을 AI에 보낼지 (기본 켬)
        // 말 끊기(안내를 읽는 중 사람이 말하면 멈추고 듣기). 기본은 끔(2026-11-13): 도움 모드는 AI가 말하는 중 어르신이 끊을 수 없다.
        // 주문 흐름 방식에서 쓰려면 local.properties 에 kl.bargeIn=true (main 의 기본값은 켬이었다 — 합치면서 도움 모드 요구에 맞춰 바꿈)
        buildConfigField("boolean", "BARGE_IN", (prop("kl.bargeIn") == "true").toString())
        buildConfigField("boolean", "SHOW_RECOGNITION", (prop("kl.showRecognition") == "true").toString())
    }
    buildFeatures { buildConfig = true }

    // 플레이버 없음: Fake · mock 없이 항상 실구현 (2일차 결정, docs/decisions.md 5장)

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
