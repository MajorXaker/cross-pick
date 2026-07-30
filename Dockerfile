FROM eclipse-temurin:21-jdk-jammy

WORKDIR /build

# Copy the entire project
COPY . .

# Build the plugin
RUN ./gradlew buildPlugin --no-daemon

# The artifact will be at build/distributions/crosspick-1.0.0.zip
