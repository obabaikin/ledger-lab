plugins {
    java
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.node.gradle)
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

val pageLint by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(pageTypes)
    npmCommand = listOf("run", "lint")
    inputs.files(pageSources, "openapi.json")
    outputs.upToDateWhen { true }
}

tasks.check {
    dependsOn(pageKnip, pageLint)
}

val pageBuild by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(pageTypes)
    npmCommand = listOf("run", "build")
    inputs.files(pageSources, "openapi.json")
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
        // The ledger, the gateway and reporting already serve periods; control still calls the bank of runs and is checked against
        // the specs of those services until it moves to periods itself.
        "ledger.spec" to file("src/test/runs-specs/ledger.json"),
        "gateway.spec" to file("src/test/runs-specs/gateway.json"),
        "generator.spec" to rootProject.file("generator/openapi.json"),
        "reporting.spec" to file("src/test/runs-specs/reporting.json"),
    )
    inputs.files(specs.values).withPropertyName("serviceSpecs")
    specs.forEach { (property, file) -> systemProperty(property, file.path) }
}
