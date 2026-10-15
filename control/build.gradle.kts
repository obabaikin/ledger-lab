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

// The page shows the commit it was built from; a new commit with the same sources must still rebuild it.
val gitState = providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=no") }.standardOutput.asText
val gitHead = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText

val pageBuild by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(pageTypes)
    npmCommand = listOf("run", "build")
    inputs.files(pageSources, "openapi.json")
    inputs.property("gitHead", gitHead)
    inputs.property("gitState", gitState)
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
    implementation("org.springframework.boot:spring-boot-starter-validation")
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
