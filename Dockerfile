# ===== Build stage =====
FROM gradle:8.12-jdk21 AS build
WORKDIR /home/gradle/src

# Copy project files
COPY --chown=gradle:gradle . .

# Build fat JAR
RUN gradle :app:fatJar --no-daemon

# ===== Runtime stage =====
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the fat JAR from build stage
COPY --from=build /home/gradle/src/app/build/libs/*-all.jar app.jar

# Railway / Render / Fly.io set PORT automatically
ENV PORT=8080
EXPOSE 8080

# Run the application
CMD ["java", "-jar", "app.jar"]
