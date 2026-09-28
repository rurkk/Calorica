FROM gradle:8.14.3-jdk21@sha256:21bd311ed01360c189b8870c6b6e988199ff10f72d445d02fb39d3cff9da91d7 AS build
USER gradle
RUN mkdir -p /home/gradle/project
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle src/main ./src/main
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813504bf4129be2d09d7
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 calorica && useradd --uid 10001 --gid calorica --no-create-home calorica
WORKDIR /app
COPY --from=build --chown=10001:10001 /home/gradle/project/build/libs/calorica-backend.jar app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
