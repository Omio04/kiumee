// 어댑터 모듈 — 소유: P2
// 의존 허용: :domain · :core-model 만 (다른 어댑터 의존 시 빌드 실패)
// 계약 타입은 :core-model 한 곳에만 있다 (P2 브랜치의 단독 빌드용 복사본 src/standalone은 통합할 때 지움).
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "kr.kiosklens.vision"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 계약 예시 JSON은 저장소 루트 testdata/contract 하나만 쓴다 (서버 · :agent와 같은 정답지).
    // 테스트는 클래스패스의 /contract/*.json 으로 읽는다.
    sourceSets["test"].resources.srcDir(rootProject.file("testdata"))
}

dependencies {
    implementation(project(":domain"))                           // :core-model(계약)은 :domain을 통해 들어온다
    implementation(libs.androidx.camera.core)                    // CameraFrame.image = ImageProxy
    implementation(libs.mlkit.text.korean)                       // 기기 안 한국어 글자 인식
    implementation(libs.kotlinx.coroutines.play.services)        // ML Kit Task → suspend
    implementation(libs.kotlinx.serialization.json)              // 스냅샷 JSON 저장 (SnapshotJson)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
