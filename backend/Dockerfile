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

FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c AS runtime
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/agendamento-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
