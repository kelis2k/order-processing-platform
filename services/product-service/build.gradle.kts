plugins {
    alias(libs.plugins.spring.boot)
}

ext["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.mongo)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.webmvc)
    implementation(libs.spring.kafka)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.avro)
    implementation(libs.grpc.spring)
    implementation(project(":proto-contracts"))
    implementation(project(":avro-schemas"))
    implementation(project(":common"))
    implementation(libs.logstash.logback.encoder)
    implementation(libs.mapstruct)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstruct.processor)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.spring.boot.starter.test)
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