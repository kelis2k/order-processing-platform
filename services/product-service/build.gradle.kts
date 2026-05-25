plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.mongo)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.kafka)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.avro)
    implementation(libs.grpc.spring)
    implementation(project(":proto-contracts"))
    implementation(project(":avro-schemas"))
    implementation(project(":common"))
    implementation(libs.mapstruct)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstruct.processor)
}