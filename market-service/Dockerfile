FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build/market-service
COPY market-service/.mvn ./.mvn
COPY market-service/mvnw ./mvnw
COPY market-service/pom.xml ./pom.xml
COPY market-service/market-contract/pom.xml ./market-contract/pom.xml
COPY market-service/market-core/pom.xml ./market-core/pom.xml
COPY market-service/market-adapters/pom.xml ./market-adapters/pom.xml
COPY market-service/market-boot/pom.xml ./market-boot/pom.xml
COPY docs/openapi/market-service-v2.yaml /build/docs/openapi/market-service-v2.yaml
RUN ./mvnw -B -q -DskipTests dependency:go-offline

COPY market-service/market-contract ./market-contract
COPY market-service/market-core ./market-core
COPY market-service/market-adapters ./market-adapters
COPY market-service/market-boot ./market-boot
RUN ./mvnw -B -q -DskipTests package
RUN java -Djarmode=tools -jar market-boot/target/market-service.jar \
    extract --layers --destination /layers

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
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "market-service.jar"]
