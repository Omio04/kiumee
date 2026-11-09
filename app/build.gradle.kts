// 조립점 — 소유: P1. 로직 없이 어댑터를 골라 끼우기만 한다 (설계서 16.8).
plugins { alias(libs.plugins.android.application) }

android {
    namespace = "kr.kiosklens.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "kr.kiosklens.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    // real: 실구현 / mock: 모든 포트에 Fake 바인딩 (설계서 16.8)
    flavorDimensions += "impl"
    productFlavors {
        create("real") { dimension = "impl" }
        create("mock") {
            dimension = "impl"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
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
    testImplementation(libs.junit)
}
