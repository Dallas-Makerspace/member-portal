# Use eclipse temurin JRE 21 (smaller, more secure runtime-only image)
FROM eclipse-temurin:21-jre-alpine

# Pick up OS security fixes published after the base image was built; Trivy fails the deploy on
# fixed HIGH/CRITICAL CVEs (e.g. libexpat, brotli-libs).
RUN apk upgrade --no-cache

# git: the IT assistant reads code and history from local clones of public repositories.
RUN apk add --no-cache git

# Add non-root user for security
RUN addgroup -g 1001 appuser && \
    adduser -D -u 1001 -G appuser appuser

ENV TZ="America/Chicago"

# Set the working directory in the container
WORKDIR /app

# Copy the JAR file into the container
COPY build/libs/service-all.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appuser /app

# Run as non-root user
USER appuser

# Expose the port the application runs on
EXPOSE 8081

# Command to run the application
# -Dnetworkaddress.cache.ttl=5: short DNS cache TTL for this long-lived JVM - matches
# ui/prod.Dockerfile (see its comment for why).
CMD ["java", "-Dnetworkaddress.cache.ttl=5", "-Dnetworkaddress.cache.negative.ttl=0", "-jar", "app.jar"]
