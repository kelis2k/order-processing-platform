plugins {
    id("com.google.protobuf") version "0.9.4"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.grpc.netty)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.63.0"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
}