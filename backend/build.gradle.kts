import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    kotlin("plugin.jpa") version "2.4.10"
}

group = "dev.deskseed"
version = "0.1.0-SNAPSHOT"

description = "Deskseed self-hosted support ticketing backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.0"
extra["testcontainersVersion"] = "2.0.5"

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.0")
    implementation("com.scalar.maven:scalar-webmvc:0.6.62")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.yaml:snakeyaml")
    testImplementation(platform("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // The suite has distinct Spring integration contexts; retain a bounded set in CI.
    maxHeapSize = "1g"
    systemProperty("spring.test.context.cache.maxSize", "8")
}

tasks.processResources {
    from(rootProject.file("../api")) {
        include(
            "core-api-outline-v1.yaml",
            "customer-identity-api-v1.yaml",
            "platform-api-outline-v1.yaml",
        )
        into("static/api-docs/specs")
    }
}
