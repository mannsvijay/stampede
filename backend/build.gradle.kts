plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    // Applies Spring Boot's BOM, so the dependencies below need no version numbers.
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.stampede"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Flyway runs db/migration/V*.sql on startup. Since Flyway 10, PostgreSQL support is a separate module.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Regenerates the committed demo venue file. The generator lives in the test source set (a dev tool,
// not shipped in the app jar); VenueGeneratorTest fails if the committed file is out of date.
tasks.register<JavaExec>("generateVenue") {
    group = "stampede"
    description = "Regenerates src/main/resources/seed/venues/demo-arena.json"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.stampede.inventory.seed.VenueGenerator")
    args(file("src/main/resources/seed/venues/demo-arena.json").absolutePath)
}
