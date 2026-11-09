plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// 아키텍처 규칙 (설계서 16.2 · 16.9): 어댑터끼리 project 의존 금지.
val adapters = setOf(":vision", ":voice", ":agent", ":overlay")
val pureModules = setOf(":core-model", ":domain")
subprojects {
    afterEvaluate {
        configurations.configureEach {
            dependencies.withType(ProjectDependency::class.java).configureEach {
                val target = path
                val self = project.path
                if (target == self) return@configureEach // AGP가 내부적으로 만드는 자기 의존은 제외
                if (self in adapters && target in adapters) {
                    throw GradleException("아키텍처 위반: 어댑터 $self → $target 직접 의존 금지 (:domain을 거칠 것)")
                }
                if (self in pureModules && target !in pureModules) {
                    throw GradleException("아키텍처 위반: 순수 모듈 $self → $target 의존 금지")
                }
            }
        }
    }
}
