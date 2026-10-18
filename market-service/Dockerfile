FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package
RUN java -Djarmode=tools -jar target/market-service-1.0.0.jar extract --layers --destination /layers

FROM eclipse-temurin:21.0.6_7-jre-jammy AS runtime

RUN apt-get update && \
    apt-get install --yes --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid app --home-dir /app app
WORKDIR /app
COPY --from=builder --chown=app:app /layers/dependencies/ ./
COPY --from=builder --chown=app:app /layers/spring-boot-loader/ ./
COPY --from=builder --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /layers/application/ ./
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "market-service-1.0.0.jar"]
