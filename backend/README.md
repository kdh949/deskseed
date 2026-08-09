# Backend

The generated archive contains a checksum-verifying `gradlew` bootstrap script rather than the binary Gradle Wrapper JAR. It downloads the official Gradle 9.7.0 binary distribution, verifies its published SHA-256 checksum, and executes Gradle.

After the first successful local build, normalize this to the official wrapper and commit the generated JAR/scripts:

```bash
./gradlew wrapper --gradle-version 9.7.0 --distribution-type bin 
./gradlew wrapper
```

The application uses Java 21, Kotlin, Spring Boot, Spring MVC, Spring Data JPA, Flyway, PostgreSQL, and Spring Modulith.
