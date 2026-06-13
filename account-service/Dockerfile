FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY account-service/src account-service/src
RUN mvn -B -pl account-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /workspace/account-service/target/account-service-*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
