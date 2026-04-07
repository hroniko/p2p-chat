# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy Maven and dependencies first for better caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install fontconfig for thumbnail generation
RUN apk add --no-cache fontconfig

# Create directories for persistent data
RUN mkdir -p /data/files /data/logs

# Copy the JAR
COPY --from=build /app/target/*.jar app.jar

# Environment variables
ENV SPRING_DATASOURCE_URL=jdbc:sqlite:/data/chat.db
ENV LOGGING_FILE_NAME=/data/logs/p2p-chat.log

# Expose ports
EXPOSE 8089 9090 45678/udp

# Volumes for persistent data
VOLUME ["/data"]

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8089/api/info || exit 1

ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xmx512m", "-jar", "app.jar"]
