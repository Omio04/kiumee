plugins {
    id("com.android.library")
}
val standaloneDemo = findProject(":core-model") == null
val legacyKotlin = com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION.substringBefore('.').toInt() < 9
if (legacyKotlin) apply(plugin = "org.jetbrains.kotlin.android")

android {
    namespace = "kr.kiosklens.vision"
    compileSdk = if (standaloneDemo) 35 else 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // ZIP demo only; the team application uses its actual shared module.
    if (standaloneDemo) {
        sourceSets["main"].java.srcDir("src/standalone/kotlin")
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
val coroutinesVersion = if (standaloneDemo) "1.9.0" else "1.10.2"
dependencies {
    findProject(":core-model")?.let { api(project(it.path)) }
    api("androidx.camera:camera-core:1.4.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}
