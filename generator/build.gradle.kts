plugins {
    java
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(libs.springdoc.webmvc.api)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The bank's published API: the generator's model of it is checked against this file, not against the bank's classes.
    val bankSpec = rootProject.file("gateway/openapi.json")
    inputs.file(bankSpec).withPropertyName("bankSpec")
    systemProperty("bank.spec", bankSpec.path)
    val spec = file("openapi.json")
    inputs.files(spec).withPropertyName("openapiSpec")
    systemProperty("openapi.spec", spec.path)
    systemProperty("openapi.actual", layout.buildDirectory.file("openapi.json").get().asFile.path)
}
