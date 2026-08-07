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
# Cloudflare) attempt the IPv6 address first, fail/timeout, then fall back to IPv4 — adding ~1-2s to
# every single such call. All-IPv4 in-network calls (e.g. to member-profile-service) are unaffected
# since Docker's embedded DNS never returns AAAA records for sibling containers.
#
# Previously reverted (84df0b3) not because this flag was itself faulty, but because it happened
# to ship in the same commit as an unrelated LDAP connection-pool bug (missing @Singleton scope on
# Dagger @Provides methods, fixed separately in 6eb0761) that caused a production hang the two got
# tangled together in the incident. Re-applying now that the real cause is confirmed fixed and
# stable on its own.
#
# -Xlog:gc*:stdout:time,level: TEMPORARY diagnostic logging. Packet capture on 2026-08-07 showed
# outbound Keycloak calls start fast after a fresh deploy (~100-250ms) and climb steadily with
# load (~600ms+ within a minute), with the extra time entirely in our own process's reaction time
# to I/O (network RTTs and Keycloak's own response time stayed constant between fast/slow samples).
# That pattern - degrades with request volume, resets clean on restart, no corresponding growth in
# RSS or thread count - points at GC pause frequency rising with allocation rate. This flag lets us
# correlate GC pause timestamps against request timing to confirm. Remove once diagnosed.
CMD ["java", "-Djava.net.preferIPv4Stack=true", "-Xlog:gc*:stdout:time,level", "-jar", "app.jar"]
