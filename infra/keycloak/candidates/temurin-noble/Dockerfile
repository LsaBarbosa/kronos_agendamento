# syntax=docker/dockerfile:1.7
ARG UBUNTU_IMAGE=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b
ARG TEMURIN_JRE_VERSION=21.0.13+9
ARG GUAVA_VERSION=33.7.2-jre

FROM --platform=linux/amd64 ${UBUNTU_IMAGE} AS temurin-verify
ARG UBUNTU_IMAGE
ARG TEMURIN_JRE_VERSION
ARG TEMURIN_SHA256=a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates gnupg && \
    rm -rf /var/lib/apt/lists/*
COPY adoptium-release-key.asc /tmp/adoptium-release-key.asc
COPY OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz.sig /tmp/temurin-jre.tar.gz.sig
COPY OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz.sha256.txt /tmp/temurin-jre.tar.gz.sha256.txt
ADD --checksum=sha256:${TEMURIN_SHA256} \
    https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz \
    /tmp/temurin-jre.tar.gz
RUN set -eu; \
    cd /tmp; \
    sed 's#OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz#temurin-jre.tar.gz#' \
      temurin-jre.tar.gz.sha256.txt | sha256sum -c -; \
    gpg --batch --import /tmp/adoptium-release-key.asc; \
    fingerprint="$(gpg --batch --with-colons --fingerprint 3B04D753C9050D9A5D343F39843C48A565F8F04B | awk -F: '$1 == "fpr" { print $10; exit }')"; \
    test "${fingerprint}" = 3B04D753C9050D9A5D343F39843C48A565F8F04B; \
    gpg --batch --verify /tmp/temurin-jre.tar.gz.sig /tmp/temurin-jre.tar.gz; \
    mkdir -p /out/opt/java/openjdk; \
    tar -xzf /tmp/temurin-jre.tar.gz --strip-components=1 -C /out/opt/java/openjdk; \
    /out/opt/java/openjdk/bin/java -version 2>&1 | grep -F 'Temurin-21.0.13+9'

FROM --platform=linux/amd64 ${KEYCLOAK_IMAGE} AS keycloak-upstream

FROM --platform=linux/amd64 ${UBUNTU_IMAGE} AS runtime
ARG UBUNTU_IMAGE
ARG KEYCLOAK_IMAGE
ARG TEMURIN_JRE_VERSION
ARG GUAVA_VERSION
ENV HOME=/opt/keycloak \
    LANG=en_US.UTF-8 \
    JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:${PATH} \
    KC_RUN_IN_CONTAINER=true

COPY --from=temurin-verify --chown=0:0 /out/opt/java/openjdk/ /opt/java/openjdk/
COPY --from=keycloak-upstream --chown=1000:0 /opt/keycloak/ /opt/keycloak/
COPY --chown=0:0 temurin-21.0.13+9-release-metadata.json /usr/share/keycloak-image/temurin-release-metadata.json

# Keycloak 26.8.1's Quarkus index uses this original filename. Replace the
# artifact contents with the checksum-verified upstream Guava 33.7.2 release.
ADD --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c \
    https://repo.maven.apache.org/maven2/com/google/guava/guava/${GUAVA_VERSION}/guava-${GUAVA_VERSION}.jar \
    /tmp/guava-${GUAVA_VERSION}.jar
RUN set -eu; \
    install -m 0644 /tmp/guava-${GUAVA_VERSION}.jar \
      /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar; \
    rm /tmp/guava-${GUAVA_VERSION}.jar; \
    test "$(java -version 2>&1 | sed -n '1p')" = 'openjdk version "21.0.13" 2026-10-20 LTS'; \
    mkdir -p /usr/share/keycloak-image; \
    { \
      printf 'Keycloak=26.8.1\n'; \
      printf 'KeycloakSource=%s\n' "${KEYCLOAK_IMAGE}"; \
      . /etc/os-release; printf 'Distribution=%s\n' "${PRETTY_NAME}"; \
      printf 'Architecture=%s\n' "$(dpkg --print-architecture)"; \
      printf 'UbuntuBase=%s\n' "${UBUNTU_IMAGE}"; \
      printf 'TemurinArtifact=org.eclipse.temurin:jre:%s\n' "${TEMURIN_JRE_VERSION}"; \
      printf 'TemurinRelease=https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13+9\n'; \
      printf 'TemurinSHA256=a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd\n'; \
      printf 'TemurinSignerFingerprint=3B04D753C9050D9A5D343F39843C48A565F8F04B\n'; \
      printf 'TemurinSBOM=Official CycloneDX SBOM retained in candidate evidence\n'; \
      printf 'GuavaArtifact=com.google.guava:guava:%s\n' "${GUAVA_VERSION}"; \
      printf 'GuavaSHA256=b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c\n'; \
      dpkg-query -W -f='${binary:Package}=${Version}\n' | sort; \
    } > /usr/share/keycloak-image/BUILD-METADATA.txt; \
    chmod 0644 /usr/share/keycloak-image/BUILD-METADATA.txt

LABEL org.opencontainers.image.title="Kronos Keycloak Temurin Noble prototype" \
      org.opencontainers.image.version="26.8.1" \
      org.opencontainers.image.base.name="ubuntu:24.04" \
      org.opencontainers.image.base.digest="sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852" \
      org.kronos.keycloak.upstream="quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b" \
      org.kronos.temurin.version="21.0.13+9" \
      org.kronos.temurin.sha256="a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd" \
      org.kronos.guava.version="33.7.2-jre"

USER 1000:0
WORKDIR /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
