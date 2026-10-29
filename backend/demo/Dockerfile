FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B verify

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home messenger     && install --directory --owner 10001 /var/lib/messenger/files
# Uploaded files; mount a shared volume here so every replica sees the same files.
ENV FILE_STORAGE_PATH=/var/lib/messenger/files
WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/messenger.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/messenger.jar"]
