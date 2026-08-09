FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache curl unzip
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S deskseed && adduser -S deskseed -G deskseed
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER deskseed
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
