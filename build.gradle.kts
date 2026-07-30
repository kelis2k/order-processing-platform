import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.api.tasks.PathSensitivity

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency) apply false
    id("org.owasp.dependencycheck") version "12.1.0" apply false
}

group = "ru.potekhincode"
version = "1.0-SNAPSHOT"

val jacksonBomVersion = libs.versions.jackson.get()

val jacocoExclusions = listOf(
    "**/*Application*",
)

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.owasp.dependencycheck")

    extra["jackson-bom.version"] = jacksonBomVersion

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    configurations.all {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }

    repositories {
        mavenCentral()
        maven {
            url = uri("https://packages.confluent.io/maven/")
        }
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.10.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        inputs.file(rootProject.file("lombok.config"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("lombokConfig")
    }


    configure<DependencyCheckExtension> {
        nvd {
            apiKey = (findProperty("nvdApiKey") as String?) ?: System.getenv("NVD_API_KEY")
            delay = 4000
        }
        failBuildOnCVSS = 7.0f
        formats = listOf("HTML", "SARIF", "JSON")
        data { directory = "$rootDir/.dependency-check-data" }
        suppressionFile = "$rootDir/config/owasp-suppressions.xml"
        scanConfigurations = listOf("runtimeClasspath")
        analyzers {
            assemblyEnabled = false      // .NET — не наш стек
            nodeEnabled = false          // npm — не наш стек
            ossIndexEnabled = false      // сетевой Sonatype OSS Index: анонимный rate-limit роняет анализ; полагаемся на NVD
        }
    }


    apply(plugin = "jacoco")

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.withType<Test>().configureEach {
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        classDirectories.setFrom(
            classDirectories.files.map { fileTree(it) { exclude(jacocoExclusions) } }
        )
    }


    if (path.startsWith(":services:")) {
        tasks.withType<JacocoCoverageVerification>().configureEach {
            dependsOn(tasks.withType<Test>())
            classDirectories.setFrom(
                classDirectories.files.map { fileTree(it) { exclude(jacocoExclusions) } }
            )
            violationRules {
                rule {
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn(tasks.withType<JacocoCoverageVerification>())
        }
    }
}
