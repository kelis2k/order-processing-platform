import java.security.KeyPairGenerator
import java.util.Base64

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
    implementation(libs.springdoc.webmvc)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.avro)
    implementation(libs.spring.kafka)
    implementation(libs.mapstruct)
    implementation(libs.jjwt.api)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation(project(":avro-schemas"))
    implementation("com.nimbusds:nimbus-jose-jwt")
    implementation(libs.logstash.logback.encoder)
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

val generateDevJwtKeys = tasks.register("generateDevJwtKeys") {
    description = "Выпускает dev-ключи RS256, если их ещё нет (каталог в .gitignore)"
    val keysDir = layout.projectDirectory.dir("src/main/resources/keys").asFile
    outputs.dir(keysDir)
    onlyIf { !keysDir.resolve("jwt-private.pem").exists() }
    doLast {
        keysDir.mkdirs()
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        fun pem(label: String, der: ByteArray) =
            "-----BEGIN $label-----\n${encoder.encodeToString(der)}\n-----END $label-----\n"
        keysDir.resolve("jwt-private.pem").writeText(pem("PRIVATE KEY", pair.private.encoded))
        keysDir.resolve("jwt-public.pem").writeText(pem("PUBLIC KEY", pair.public.encoded))
    }
}

tasks.named("processResources") {
    dependsOn(generateDevJwtKeys)
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