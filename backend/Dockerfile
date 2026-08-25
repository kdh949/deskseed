FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache curl unzip
WORKDIR /workspace/backend
COPY backend/gradlew backend/gradlew.bat backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew
COPY backend/src ./src
COPY api/core-api-outline-v1.yaml api/customer-identity-api-v1.yaml api/platform-api-outline-v1.yaml ../api/
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S deskseed && adduser -S deskseed -G deskseed
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/*.jar app.jar
USER deskseed
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
