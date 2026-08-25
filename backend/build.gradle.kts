import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin

abstract class VerifySelectedTestCategories : DefaultTask() {
    @get:Input
    abstract val selectedCategories: ListProperty<String>

    @get:Input
    abstract val allowedCategories: SetProperty<String>

    @TaskAction
    fun verify() {
        val selected = selectedCategories.get()
        if (selected.isEmpty()) {
            throw GradleException("ciSelectedTest requires -PdeskseedTestTags with at least one primary category")
        }
        val unknown = selected.toSet() - allowedCategories.get()
        if (unknown.isNotEmpty()) {
            throw GradleException("ciSelectedTest received unknown categories: ${unknown.sorted().joinToString()}")
        }
    }
}

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
    implementation("com.scalar.maven:scalar-webmvc:0.6.65")
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
    testImplementation("org.junit.platform:junit-platform-launcher")
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
    maxParallelForks = 1
    systemProperty("spring.test.context.cache.maxSize", "8")

    if (providers.gradleProperty("deskseedTestContextCacheStats").orNull == "true") {
        systemProperty("logging.level.org.springframework.test.context.cache", "DEBUG")
        testLogging.showStandardStreams = true
    }

    providers.gradleProperty("deskseedTestClassOrderSeed").orNull?.let { seed ->
        systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$Random")
        systemProperty("junit.jupiter.execution.order.random.seed", seed)
    }
}

val testSourceSet = sourceSets.test.get()
val primaryTestCategories = linkedSetOf("fast", "contract", "integration", "migration", "slow")

val verifyTestCategories = tasks.register<JavaExec>("verifyTestCategories") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that every Deskseed test has exactly one primary category"
    dependsOn(tasks.testClasses)
    classpath = testSourceSet.runtimeClasspath
    mainClass = "dev.deskseed.testsupport.category.TestCategoryVerifier"
}

mapOf(
    "fastTest" to "fast",
    "contractTest" to "contract",
    "integrationTest" to "integration",
    "migrationTest" to "migration",
    "slowTest" to "slow",
).forEach { (taskName, category) ->
    tasks.register<Test>(taskName) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs Deskseed $category tests"
        dependsOn(verifyTestCategories)
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags(category)
        }
    }
}

val selectedCiTags = providers.gradleProperty("deskseedTestTags")
    .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty).distinct() }
    .orElse(emptyList())

val verifySelectedTestCategories = tasks.register<VerifySelectedTestCategories>("verifySelectedTestCategories") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails closed when CI test categories are empty or unknown"
    selectedCategories.set(selectedCiTags)
    allowedCategories.set(primaryTestCategories)
}

tasks.register<Test>("ciSelectedTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the explicitly selected Deskseed test categories in one test process"
    dependsOn(verifyTestCategories, verifySelectedTestCategories)
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    val tags = selectedCiTags.get()
    useJUnitPlatform {
        if (tags.isNotEmpty()) {
            includeTags(*tags.toTypedArray())
        }
    }
}

tasks.test {
    dependsOn(verifyTestCategories)
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
