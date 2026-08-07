# Use eclipse temurin JRE 21 (smaller, more secure runtime-only image)
FROM eclipse-temurin:21-jre-alpine

# Add non-root user for security
RUN addgroup -g 1001 appuser && \
    adduser -D -u 1001 -G appuser appuser

ENV TZ="America/Chicago"
ENV ENV_DEV_MODE=false

# Set the working directory in the container
WORKDIR /app

# Copy the JAR file into the container
COPY build/libs/ui-all.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appuser /app

# Run as non-root user
USER appuser

# Expose the port the application runs on
EXPOSE 8000

# Command to run the application
# -Djava.net.preferIPv4Stack=true: this container has no working IPv6 egress. Without this flag,
# outbound calls to dual-stack hosts (e.g. Keycloak, which resolves to both A and AAAA records via
# Cloudflare) attempt the IPv6 address first, fail/timeout, then fall back to IPv4 — adding ~1s to
# every single such call. All-IPv4 in-network calls (e.g. to member-profile-service) are unaffected
# since Docker's embedded DNS never returns AAAA records for sibling containers.
CMD ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "app.jar"]
