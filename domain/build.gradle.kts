// 순수 Kotlin 모듈: 상태머신 · 유스케이스 · 정책 (설계서 16.5)
plugins { alias(libs.plugins.kotlin.jvm) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core-model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
