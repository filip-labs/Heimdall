# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS builder
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -DskipTests package && cp target/*.jar heimdall.jar

FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c AS runtime
RUN groupadd --gid 10001 heimdall \
    && useradd --uid 10001 --gid heimdall --no-create-home \
       --shell /usr/sbin/nologin heimdall
WORKDIR /app
COPY --from=builder --chmod=0444 /build/heimdall.jar /app/heimdall.jar
USER 10001:10001
EXPOSE 8080

# curl is already supplied by the Temurin Ubuntu runtime image.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD test "$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 4 http://127.0.0.1:8080/actuator/health)" = 200
ENTRYPOINT ["java", "-jar", "/app/heimdall.jar"]
