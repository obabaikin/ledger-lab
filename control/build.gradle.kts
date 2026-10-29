plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.node.gradle)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        javaParameters = true
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

node {
    download = true
    version = "24.21.0"
    npmInstallCommand = "ci"
    nodeProjectDir = file("page")
}

val pageTypes by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "types")
    inputs.file("openapi.json")
    outputs.file("page/src/api/schema.d.ts")
}

val pageSources = fileTree("page") { exclude("node_modules", "src/api/schema.d.ts") }

val pageTest by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(pageTypes)
    npmCommand = listOf("run", "test")
    inputs.files(pageSources, "openapi.json")
    outputs.upToDateWhen { true }
}

val pageKnip by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(pageTypes)
    npmCommand = listOf("run", "knip")
    inputs.files(pageSources, "openapi.json")
    outputs.upToDateWhen { true }
}

tasks.check {
    dependsOn(pageKnip)
}

val gitDescribe = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--long", "--always", "--dirty=+changes")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

// SemVer: the commits after the release tag are build metadata (v0.4.0+11), which leaves the order to the tag.
fun semver(described: String): String {
    val match = Regex("""(v.+)-(\d+)-g[0-9a-f]+(\+changes)?""").matchEntire(described)
        ?: return described.ifEmpty { "unknown" }
    val (tag, since, changes) = match.destructured
    val metadata = listOfNotNull(since.takeIf { it != "0" }, "changes".takeIf { changes.isNotEmpty() }).joinToString(".")
    return if (metadata.isEmpty()) tag else "$tag+$metadata"
}

val buildVersion = gitDescribe.map(::semver)

springBoot {
    buildInfo {
        properties {
            version = buildVersion
            additional.put("commit", gitCommit)
        }
    }
}

val pageBuild by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(pageTypes)
    npmCommand = listOf("run", "build")
    inputs.files(pageSources, "openapi.json")
    inputs.property("buildVersion", buildVersion)
    environment.put("BUILD_VERSION", buildVersion)
    outputs.dir(layout.buildDirectory.dir("page"))
}

tasks.processResources {
    from(pageBuild) { into("static") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(libs.springdoc.webmvc.api)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    dependsOn(pageTest)
    useJUnitPlatform()
    inputs.files("openapi.json").withPropertyName("openapiSpec")
    systemProperty("openapi.dir", projectDir.path)
    systemProperty("openapi.actualDir", layout.buildDirectory.get().asFile.path)
    // Control knows the services only through their specs (ADR 0011), not through their classes.
    val specs = mapOf(
        "ledger.spec" to rootProject.file("ledger-service/openapi.json"),
        "gateway.spec" to rootProject.file("gateway/openapi-internal.json"),
        "generator.spec" to rootProject.file("generator/openapi.json"),
        "reporting.spec" to rootProject.file("reporting/openapi.json"),
    )
    inputs.files(specs.values).withPropertyName("serviceSpecs")
    specs.forEach { (property, file) -> systemProperty(property, file.path) }
}
