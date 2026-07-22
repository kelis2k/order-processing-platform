import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension

plugins {
    java
    id("org.springframework.boot") version "3.4.5" apply false
    id("io.spring.dependency-management") version "1.1.5" apply false
    id("org.owasp.dependencycheck") version "12.1.0" apply false
}

group = "ru.potekhincode"
version = "1.0-SNAPSHOT"

val jacksonBomVersion = libs.versions.jackson.get()

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.owasp.dependencycheck")

    extra["jackson-bom.version"] = jacksonBomVersion

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    // OWASP Dependency-Check — per-project (не aggregate: агрегатор резолвит чужие
    // конфигурации, что Gradle 9 запрещает как unsafe). Каждый модуль сканирует свой
    // classpath; кэш NVD и suppression — общие по $rootDir. Типизированный аксессор
    // dependencyCheck{} в subprojects недоступен (плагин применён через apply()),
    // поэтому configure<DependencyCheckExtension>.
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
}
