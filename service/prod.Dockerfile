# Use eclipse temurin JRE 21 (smaller, more secure runtime-only image)
FROM eclipse-temurin:21-jre-alpine

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
# -Djava.net.preferIPv4Stack=true: this container has no working IPv6 egress. Without this flag,
# outbound calls to dual-stack hosts (e.g. Discourse, MakerManager/WHMCS) attempt the IPv6 address
# first, fail/timeout, then fall back to IPv4 — adding real latency to every such call. See the
# matching fix in ui/prod.Dockerfile for the fuller writeup.
CMD ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
