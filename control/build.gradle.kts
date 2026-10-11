plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        javaParameters = true
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
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
    useJUnitPlatform()
    inputs.files("openapi.json").withPropertyName("openapiSpec")
    systemProperty("openapi.dir", projectDir.path)
    systemProperty("openapi.actualDir", layout.buildDirectory.get().asFile.path)
    // Control knows the services only through their specs (ADR 0011), not through their classes.
    val specs = mapOf(
        "ledger.spec" to rootProject.file("ledger-service/openapi.json"),
        "gateway.spec" to rootProject.file("gateway/openapi-internal.json"),
        "generator.spec" to rootProject.file("generator/openapi.json"),
    )
    inputs.files(specs.values).withPropertyName("serviceSpecs")
    specs.forEach { (property, file) -> systemProperty(property, file.path) }
}
