# The runtime uses the same signature- and checksum-verified Temurin release
# as Keycloak. Build Java and application artifacts remain outside the runtime.
# syntax=docker/dockerfile:1.7
ARG UBUNTU_IMAGE=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
ARG CA_CERTIFICATES_VERSION=20260816ubuntu0.24.04.1
ARG CURL_VERSION=8.5.0-2ubuntu10.16
ARG LIBBROTLI_VERSION=1.1.0-2ubuntu0.1

FROM ${UBUNTU_IMAGE} AS temurin-verify
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl gnupg && \
    rm -rf /var/lib/apt/lists/*
COPY --from=temurin /21.0.13+9/ /tmp/temurin/
RUN /tmp/temurin/verify-and-extract.sh /out/opt/java/openjdk /out/temurin-build-metadata.txt

FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
COPY src/e2e /workspace/e2e-src
ARG INCLUDE_E2E_FIXTURES=false
RUN if [ "$INCLUDE_E2E_FIXTURES" = "true" ]; then cp -R /workspace/e2e-src/java/com /workspace/src/main/java/; fi \
    && ./mvnw -B -ntp -DskipTests -DskipITs package

FROM ${UBUNTU_IMAGE} AS runtime
ARG CA_CERTIFICATES_VERSION
ARG CURL_VERSION
ARG LIBBROTLI_VERSION
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:${PATH}
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      "ca-certificates=${CA_CERTIFICATES_VERSION}" "curl=${CURL_VERSION}" \
      "libbrotli1=${LIBBROTLI_VERSION}" && \
    test -s /etc/ssl/certs/ca-certificates.crt && \
    test "$(dpkg-query -W -f='${Version}' libbrotli1)" = "${LIBBROTLI_VERSION}" && \
    rm -rf /var/lib/apt/lists/*
COPY --from=temurin-verify --chown=0:0 /out/opt/java/openjdk/ /opt/java/openjdk/
COPY --from=temurin-verify --chown=0:0 /out/temurin-build-metadata.txt /usr/share/temurin/BUILD-METADATA.txt
COPY --from=build --chown=10001:10001 /workspace/target/agendamento-backend-*.jar /app/app.jar
RUN set -eu; \
    runtime_version="$(sed -n 's/^JavaRuntimeVersion=//p' /usr/share/temurin/BUILD-METADATA.txt)"; \
    /opt/java/openjdk/bin/java -version 2>&1 | grep -F "Temurin-${runtime_version%-LTS} (build ${runtime_version})"; \
    chmod 0444 /app/app.jar /usr/share/temurin/BUILD-METADATA.txt
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
