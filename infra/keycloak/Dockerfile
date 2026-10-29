# syntax=docker/dockerfile:1.7
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b
ARG DEBIAN_BASE=debian:forky-slim@sha256:5533be417c8ed39dbf2ac33e1fdbf08ccfa5a58e62d84238999ff98ab3357068
ARG DEBIAN_SNAPSHOT=20261028T183000Z

FROM --platform=$TARGETPLATFORM ${KEYCLOAK_IMAGE} AS keycloak-upstream
FROM --platform=$TARGETPLATFORM ${DEBIAN_BASE} AS runtime
ARG DEBIAN_SNAPSHOT
ARG KEYCLOAK_IMAGE
ARG DEBIAN_BASE
ARG OPENJDK_VERSION=21.0.13+9-1
ARG GUAVA_VERSION=33.7.2-jre

ENV HOME=/opt/keycloak \
    LANG=en_US.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/default-java \
    PATH=/usr/lib/jvm/default-java/bin:${PATH} \
    KC_RUN_IN_CONTAINER=true

# Snapshot URLs and exact runtime package version make dependency resolution
# repeatable; metadata and dpkg records remain in the final image for scanners.
RUN rm -f /etc/apt/sources.list.d/* && \
    printf '%s\n' \
      "deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/${DEBIAN_SNAPSHOT}/ forky main" \
      > /etc/apt/sources.list && \
    apt-get -o Acquire::Check-Valid-Until=false update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      bash \
      ca-certificates \
      grep \
      openjdk-21-jre-headless=${OPENJDK_VERSION} \
      sed && \
    test "$(dpkg-query -W -f='${Version}' openjdk-21-jre-headless)" = "${OPENJDK_VERSION}" && \
    jvm_arch="$(dpkg --print-architecture)" && \
    ln -s "/usr/lib/jvm/java-21-openjdk-${jvm_arch}" /usr/lib/jvm/default-java && \
    test -x /usr/lib/jvm/default-java/bin/java && \
    java -version 2>&1 | grep -F '21.0.13' && \
    rm -rf /var/lib/apt/lists/*

# Only the upstream Keycloak distribution is copied; its UBI runtime/JDK is not.
COPY --from=keycloak-upstream --chown=1000:0 /opt/keycloak/ /opt/keycloak/

# The upstream 26.8.1 Quarkus index references the Guava JAR by its original
# filename. Keep that path, but replace the artifact contents with upstream
# Guava 33.7.2, retaining its Maven metadata for scanners. Checksum is from
# Maven Central.
ADD --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c \
    https://repo.maven.apache.org/maven2/com/google/guava/guava/${GUAVA_VERSION}/guava-${GUAVA_VERSION}.jar \
    /tmp/guava-${GUAVA_VERSION}.jar
RUN install -m 0644 /tmp/guava-${GUAVA_VERSION}.jar \
      /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar && \
    rm /tmp/guava-${GUAVA_VERSION}.jar && \
    mkdir -p /usr/share/keycloak-image && \
    { \
      printf 'Keycloak=26.8.1\n'; \
      printf 'KeycloakSource=%s\n' "${KEYCLOAK_IMAGE}"; \
      . /etc/os-release; printf 'Distribution=%s\n' "${PRETTY_NAME}"; \
      printf 'Architecture=%s\n' "$(dpkg --print-architecture)"; \
      printf 'DebianBase=%s\n' "${DEBIAN_BASE}"; \
      printf 'DebianSnapshot=%s\n' "${DEBIAN_SNAPSHOT}"; \
      printf 'OpenJDKPackage=openjdk-21-jre-headless=%s\n' "${OPENJDK_VERSION}"; \
      printf 'GuavaArtifact=com.google.guava:guava:%s\n' "${GUAVA_VERSION}"; \
      printf 'GuavaSHA256=b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c\n'; \
      dpkg-query -W -f='${binary:Package}=${Version}\n' | sort; \
    } > /usr/share/keycloak-image/BUILD-METADATA.txt && \
    chmod 0644 /usr/share/keycloak-image/BUILD-METADATA.txt

LABEL org.opencontainers.image.title="Kronos Keycloak" \
      org.opencontainers.image.version="26.8.1" \
      org.opencontainers.image.base.name="debian:forky-slim" \
      org.opencontainers.image.base.digest="sha256:5533be417c8ed39dbf2ac33e1fdbf08ccfa5a58e62d84238999ff98ab3357068" \
      org.kronos.keycloak.upstream="quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b" \
      org.kronos.openjdk.version="21.0.13+9-1" \
      org.kronos.guava.version="33.7.2-jre"

USER 1000:0
WORKDIR /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
