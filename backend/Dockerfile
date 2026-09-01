FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache curl unzip
ARG PYROSCOPE_JAVA_AGENT_VERSION=2.9.1
ARG PYROSCOPE_JAVA_AGENT_SHA256=4b9aa1ba327a9ce2b1ce062578ad6b876c0d2496f15a00f0fdc4c953638cb76d
RUN curl --fail --location --silent --show-error \
      "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_JAVA_AGENT_VERSION}/pyroscope.jar" \
      --output /tmp/pyroscope.jar \
    && echo "${PYROSCOPE_JAVA_AGENT_SHA256}  /tmp/pyroscope.jar" | sha256sum -c -
WORKDIR /workspace/backend
COPY backend/gradlew backend/gradlew.bat backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew
COPY backend/src ./src
COPY api/core-api-outline-v1.yaml api/customer-identity-api-v1.yaml api/platform-api-outline-v1.yaml ../api/
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S deskseed && adduser -S deskseed -G deskseed
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/*.jar app.jar
COPY --from=build /tmp/pyroscope.jar /opt/pyroscope/pyroscope.jar
USER deskseed
EXPOSE 8080 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
