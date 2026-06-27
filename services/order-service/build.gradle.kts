plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.kafka)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.avro)
    implementation(libs.grpc.spring)
    implementation(libs.mapstruct)
    implementation(libs.reactor.core)
    implementation(project(":proto-contracts"))
    implementation(project(":avro-schemas"))
    implementation(project(":common"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstruct.processor)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.grpc.inprocess)
}