# Runtime is built from the verified Temurin 21.0.13+9 JRE over Ubuntu Noble.
# Keycloak itself is copied from the official, digest-pinned distribution.
# Build context is the repository root; candidate evidence remains versioned.
# syntax=docker/dockerfile:1.7
ARG UBUNTU_IMAGE=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b
ARG GUAVA_VERSION=33.7.2-jre

FROM ${UBUNTU_IMAGE} AS temurin-verify
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl gnupg && \
    rm -rf /var/lib/apt/lists/*
COPY infra/temurin/21.0.13+9/ /tmp/temurin/
RUN /tmp/temurin/verify-and-extract.sh /out/opt/java/openjdk /out/temurin-build-metadata.txt

FROM ${KEYCLOAK_IMAGE} AS keycloak-upstream

FROM ${UBUNTU_IMAGE} AS runtime
ARG UBUNTU_IMAGE
ARG KEYCLOAK_IMAGE
ARG GUAVA_VERSION
ARG CA_CERTIFICATES_VERSION=20260816ubuntu0.24.04.1
ENV HOME=/opt/keycloak \
    LANG=en_US.UTF-8 \
    JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:${PATH} \
    KC_RUN_IN_CONTAINER=true

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      "ca-certificates=${CA_CERTIFICATES_VERSION}" && \
    test -s /etc/ssl/certs/ca-certificates.crt && \
    rm -rf /var/lib/apt/lists/*

COPY --from=temurin-verify --chown=0:0 /out/opt/java/openjdk/ /opt/java/openjdk/
COPY --from=temurin-verify --chown=0:0 /out/temurin-build-metadata.txt /usr/share/keycloak-image/temurin-build-metadata.txt
COPY --from=keycloak-upstream --chown=1000:0 /opt/keycloak/ /opt/keycloak/
COPY --chown=0:0 infra/keycloak/candidates/temurin-noble/temurin-21.0.13+9-release-metadata.json /usr/share/keycloak-image/temurin-release-metadata.json

# Keycloak 26.8.1's Quarkus index uses this original filename. Replace the
# artifact contents with the checksum-verified upstream Guava 33.7.2 release.
ADD --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c \
    https://repo.maven.apache.org/maven2/com/google/guava/guava/${GUAVA_VERSION}/guava-${GUAVA_VERSION}.jar \
    /tmp/guava-${GUAVA_VERSION}.jar
RUN set -eu; \
    install -m 0644 /tmp/guava-${GUAVA_VERSION}.jar \
      /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar; \
    rm /tmp/guava-${GUAVA_VERSION}.jar; \
    runtime_version="$(sed -n 's/^JavaRuntimeVersion=//p' /usr/share/keycloak-image/temurin-build-metadata.txt)"; \
    java -version 2>&1 | grep -F "Temurin-${runtime_version%-LTS} (build ${runtime_version})"; \
    mkdir -p /usr/share/keycloak-image; \
    { \
      printf 'Keycloak=26.8.1\n'; \
      printf 'KeycloakSource=%s\n' "${KEYCLOAK_IMAGE}"; \
      . /etc/os-release; printf 'Distribution=%s\n' "${PRETTY_NAME}"; \
      printf 'Architecture=%s\n' "$(dpkg --print-architecture)"; \
      printf 'UbuntuBase=%s\n' "${UBUNTU_IMAGE}"; \
      cat /usr/share/keycloak-image/temurin-build-metadata.txt; \
      printf 'TemurinSBOM=Official CycloneDX SBOM retained in candidate evidence\n'; \
      printf 'GuavaArtifact=com.google.guava:guava:%s\n' "${GUAVA_VERSION}"; \
      printf 'GuavaSHA256=b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c\n'; \
      dpkg-query -W -f='${binary:Package}=${Version}\n' | sort; \
    } > /usr/share/keycloak-image/BUILD-METADATA.txt; \
    chmod 0644 /usr/share/keycloak-image/BUILD-METADATA.txt

LABEL org.opencontainers.image.title="Kronos Keycloak Temurin Noble" \
      org.opencontainers.image.version="26.8.1" \
      org.opencontainers.image.base.name="ubuntu:24.04" \
      org.opencontainers.image.base.digest="sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852" \
      org.kronos.keycloak.upstream="quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b" \
      org.kronos.guava.version="33.7.2-jre"

USER 1000:0
WORKDIR /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
