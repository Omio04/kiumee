// 어댑터 모듈 — 소유: P5
// 의존 허용: :domain · :core-model 만 (다른 어댑터 의존 시 빌드 실패)
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "kr.kiosklens.overlay"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    testImplementation(libs.junit)

    // 디버그 빌드 전용: 하이라이트 위치 확인 화면 (src/debug, OverlayCalibrationActivity). 릴리스 앱에는 들어가지 않는다.
    debugImplementation(libs.androidx.appcompat)
    debugImplementation(libs.androidx.camera.core)
    debugImplementation(libs.androidx.camera.camera2)
    debugImplementation(libs.androidx.camera.lifecycle)
    debugImplementation(libs.androidx.camera.view)
}
