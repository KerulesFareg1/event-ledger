FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/src event-gateway/src
RUN mvn -B -pl event-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /workspace/event-gateway/target/event-gateway-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
