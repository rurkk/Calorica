import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.calorica"
version = "0.1.0-SNAPSHOT"
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21; freeCompilerArgs.add("-Xjsr305=strict") } }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform { excludeTags("integration") } }
val integrationTest by tasks.registering(Test::class) {
    description = "Starts the server against a dedicated PostgreSQL database (DB_* env required)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
}
tasks.check { dependsOn(integrationTest) }
tasks.bootJar { archiveFileName = "calorica-backend.jar" }
tasks.jar { enabled = false }
