## Multi-stage build for Spring Boot (Java 17)

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src

# Build a runnable Spring Boot jar
RUN mvn -q -DskipTests package


FROM eclipse-temurin:17-jre
WORKDIR /app

# Render sets PORT; application.yml already uses server.port=${PORT:8080}
ENV PORT=8080

COPY --from=build /app/target/*.jar /app/app.jar

EXPOSE 8080

CMD ["java","-jar","/app/app.jar"]
