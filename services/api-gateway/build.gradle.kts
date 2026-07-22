plugins {
    alias(libs.plugins.spring.boot)
}

ext["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation(libs.logstash.logback.encoder)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.mockwebserver)
    runtimeOnly(libs.micrometer.registry.prometheus)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.2")
    }
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