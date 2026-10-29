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

FROM eclipse-temurin:21-jre-noble@sha256:cd35bb33859641c5374415826a5951845104093eaa401cbe9d3d9023444f1c13 AS runtime
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/agendamento-backend-*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
