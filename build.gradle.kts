import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import io.gitlab.arturbosch.detekt.getSupportedKotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "com.ledgerlab"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.allWarningsAsErrors = true
    }

    apply(plugin = "io.gitlab.arturbosch.detekt")
    configure<DetektExtension> {
        config.setFrom(rootProject.file("config/detekt.yml"))
        buildUponDefaultConfig = false
        ignoreFailures = false
    }

    // Spring's dependency management forces its Kotlin version onto every configuration, detekt's too,
    // and detekt refuses to run on a Kotlin other than the one it was compiled with.
    afterEvaluate {
        configurations.matching { it.name == "detekt" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") useVersion(getSupportedKotlinVersion())
            }
        }
    }
}
