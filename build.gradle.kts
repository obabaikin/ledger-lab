import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
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
    plugins.withId("net.ltgt.errorprone") {
        configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(25)
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
}
