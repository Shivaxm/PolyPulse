FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

RUN apk add --no-cache maven

COPY pom.xml .
COPY polypulse-web/package.json polypulse-web/package-lock.json polypulse-web/

RUN mvn dependency:go-offline -q || true

COPY src ./src
COPY polypulse-web ./polypulse-web

RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/polypulse-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-XX:+UseG1GC", "-jar", "app.jar"]
