plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.kafka)
    implementation(libs.mapstruct)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstruct.processor)
    runtimeOnly("org.postgresql:postgresql")
}