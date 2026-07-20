plugins {
    alias(libs.plugins.spring.boot)
}

ext["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.mongo)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.kafka)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.avro)
    implementation(project(":avro-schemas"))
    implementation(project(":common"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.greenmail)
    runtimeOnly(libs.micrometer.registry.prometheus)
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