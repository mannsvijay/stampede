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

dependencyManagement {
    imports {
        // Spring Boot's BOM doesn't manage Spring Modulith. 1.4.x is the line for Boot 3.5 (2.x is Boot 4).
        mavenBom("org.springframework.modulith:spring-modulith-bom:1.4.14")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Filter chain, PasswordEncoder (bcrypt).
    implementation("org.springframework.boot:spring-boot-starter-security")
    // BearerTokenAuthenticationFilter + Nimbus JOSE: verifies and issues our HS256 access tokens.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // @Valid request bodies (Jakarta Bean Validation).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Flyway runs db/migration/V*.sql on startup. Since Flyway 10, PostgreSQL support is a separate module.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Annotations only (@ApplicationModule, @NamedInterface) for the modules' package-info files.
    implementation("org.springframework.modulith:spring-modulith-api")
    // Generates the OpenAPI spec from the controllers (D-010). The -api artifact has no Swagger UI.
    // Not managed by Boot's BOM; 2.10.0 is built against Spring Boot 3.5.16 (3.x targets Boot 4).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.10.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // ApplicationModules.verify() (module model built on ArchUnit) + @ApplicationModuleTest for later.
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    // Property-based tests (M1.1, D-014). Not managed by Boot's BOM. 1.9.2 is built against JUnit
    // Platform 1.11.3, older than the 1.12.2 Boot 3.5 pins, so it runs on a newer platform (the safe
    // direction). 1.9.3 and 1.10.x target Platform 1.13/1.14 and would be forced down to 1.12.2.
    testImplementation("net.jqwik:jqwik:1.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Forward -Dstampede.harness.rounds / .seed from the Gradle command line to the test JVM (M1.3).
    // They become task inputs, so a run with a different value is never skipped as UP-TO-DATE.
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("stampede.harness.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
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
