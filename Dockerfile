FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:8-jre
WORKDIR /app
ENV PORT=8080 SPRING_PROFILES_ACTIVE=platform,supabase
RUN mkdir -p /app/data/archives && chown -R 10001:10001 /app
COPY --from=build /build/target/stocks-monitor-platform-1.0.0.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
