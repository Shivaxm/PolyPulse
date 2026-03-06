# Stage 1: Build with Maven
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy Maven wrapper and POM first (cached dependency layer)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src/ src/
# Required by frontend-maven-plugin configured in pom.xml
COPY polypulse-web/ polypulse-web/
RUN ./mvnw package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/polypulse-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
