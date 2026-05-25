plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.mongo)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.kafka)
    implementation(libs.kafka.avro.serializer)
    implementation(libs.avro)
    implementation(project(":avro-schemas"))
    implementation(project(":common"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}