# syntax=docker/dockerfile:1
# The demo server (README's "Demo server"), built from source and run on a Java runtime alone.
#   docker build -t rankedchoice-server .
#   docker run --rm -p 127.0.0.1:8080:8080 rankedchoice-server [--max-counts N] [--timeout-seconds N] ...
# docker-compose.yml does both.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Every module's POM, since the root's lists them all; the sources of the ones the server needs.
COPY pom.xml checkstyle.xml ./
COPY common/pom.xml common/
COPY library/pom.xml library/
COPY cli/pom.xml cli/
COPY server/pom.xml server/
COPY common/src common/src
COPY library/src library/src
COPY server/src server/src
# The tests ran in the build this one comes from (the pre-commit hook's); here they'd need the
# fixtures and Python, so they're skipped. Dependencies are cached between builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl server -am package -DskipTests -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S counter && adduser -S counter -G counter
COPY --from=build /src/server/target/rankedchoice-server-*.jar /app/rankedchoice-server.jar
USER counter
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/api/methods || exit 1
# The heap follows the container's memory limit; the server's own options follow, from CMD or
# docker run's arguments.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/rankedchoice-server.jar", \
    "--bind", "0.0.0.0", "--port", "8080"]
CMD []
