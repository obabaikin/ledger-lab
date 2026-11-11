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
            if (name == "compileJava") {
                val sources = source
                val moduleDir = project.projectDir
                // With OnlyNullMarked a package without @NullMarked is skipped silently and the build stays green.
                doFirst {
                    val unmarked = sources.files.groupBy { it.parentFile }
                        .filterValues { files ->
                            files.none { it.name == "package-info.java" && "@NullMarked" in it.readText() }
                        }
                        .keys.map { it.relativeTo(moduleDir).path }.sorted()
                    if (unmarked.isNotEmpty()) {
                        throw GradleException("Packages without @NullMarked in package-info.java, NullAway would skip them: $unmarked")
                    }
                }
            }
        }
    }
}
