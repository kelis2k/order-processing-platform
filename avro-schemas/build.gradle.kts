plugins {
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.avro)
}

tasks.named("generateAvroJava") {
    doFirst {
        println("Generating Avro classes...")
    }
}