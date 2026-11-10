import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import io.gitlab.arturbosch.detekt.getSupportedKotlinVersion
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.errorprone) apply false
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

    plugins.withId("net.ltgt.errorprone") {
        configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(21)
        }
        dependencies {
            "errorprone"(rootProject.libs.errorprone.core)
            "errorprone"(rootProject.libs.nullaway)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial", "-Werror"))
            options.errorprone {
                listOf("UnusedVariable", "UnusedMethod", "RemoveUnusedImports", "ReferenceEquality").forEach {
                    check(it, CheckSeverity.ERROR)
                }
                // Test code hands null to what it tests and is filled in by frameworks: checked for the rest only.
                if (name == "compileJava") {
                    check("NullAway", CheckSeverity.ERROR)
                    option("NullAway:OnlyNullMarked", "true")
                } else {
                    disable("NullAway")
                }
            }
        }
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
