# Use eclipse temurin JDK 20 as the base image
FROM maven:3-eclipse-temurin-20-alpine

# Set the working directory in the container
WORKDIR /app

# Copy the JAR file into the container
COPY build/libs/member-profile-ui-all.jar app.jar

# Expose the port the application runs on
EXPOSE 8000

# Command to run the application
CMD ["java", "-jar", "app.jar"]
