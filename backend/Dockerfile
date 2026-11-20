FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests -DskipITs package

FROM eclipse-temurin:21.0.8_9-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data/home --no-create-home codetrace \
    && mkdir -p /data/workspaces /data/home && chown -R 10001 /data
ENV CODETRACE_INGESTION_WORKSPACE_DIR=/data/workspaces
WORKDIR /app
COPY --from=build /build/target/codetrace-backend.jar app.jar
USER 10001
# Role is chosen at runtime: SPRING_PROFILES_ACTIVE=api | worker
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
