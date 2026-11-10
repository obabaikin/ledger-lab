plugins {
    java
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(libs.springdoc.webmvc.api)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The bank's published API: the generator's model of it is checked against this file, not against the bank's classes.
    // The gateway already serves periods; the generator still talks to the bank of runs and is checked against that
    // bank's spec until it moves to periods itself.
    val bankSpec = file("src/test/runs-specs/bank.json")
    inputs.file(bankSpec).withPropertyName("bankSpec")
    systemProperty("bank.spec", bankSpec.path)
    val spec = file("openapi.json")
    inputs.files(spec).withPropertyName("openapiSpec")
    systemProperty("openapi.spec", spec.path)
    systemProperty("openapi.actual", layout.buildDirectory.file("openapi.json").get().asFile.path)
}
