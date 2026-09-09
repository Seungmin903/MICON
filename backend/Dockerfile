# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-jammy@sha256:d48e921b83506f58910295b35fd02978c7f4c57fef41bceee6a460d7d97bc251 AS build

WORKDIR /workspace
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
COPY backend/gradle ./gradle
RUN chmod 0755 gradlew
COPY backend/src ./src
RUN ./gradlew --no-daemon --max-workers=1 --console=plain bootJar

FROM eclipse-temurin:21-jdk-jammy@sha256:d48e921b83506f58910295b35fd02978c7f4c57fef41bceee6a460d7d97bc251

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 micon \
    && useradd --system --uid 10001 --gid micon --home-dir /app --shell /usr/sbin/nologin micon

WORKDIR /app
COPY --from=build --chown=micon:micon /workspace/build/libs/backend-*.jar /app/backend.jar
COPY --chown=micon:micon backend/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh \
    && chmod 0444 /app/backend.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
