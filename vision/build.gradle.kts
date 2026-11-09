// 어댑터 모듈 — 소유: P2
// 의존 허용: :domain · :core-model 만 (다른 어댑터 의존 시 빌드 실패)
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "kr.kiosklens.vision"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.camera.core)          // ImageProxy
    implementation(libs.mlkit.text.korean)             // 기기 안 한국어 글자 인식
    testImplementation(libs.junit)
}
