plugins {
    alias(libs.plugins.spring.boot)
}

ext["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.avro)
    implementation(libs.spring.kafka)
    implementation(libs.mapstruct)
    implementation(libs.jjwt.api)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation(project(":avro-schemas"))
    implementation("com.nimbusds:nimbus-jose-jwt")
    runtimeOnly("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    runtimeOnly(libs.micrometer.registry.prometheus)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstruct.processor)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.kafka)
}

tasks.withType<Test> {
    environment("DOCKER_HOST", "unix:///var/run/docker.sock")
    environment("DOCKER_API_VERSION", "1.47")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    environment("HTTP_PROXY", "")
    environment("HTTPS_PROXY", "")
    environment("http_proxy", "")
    environment("https_proxy", "")
}