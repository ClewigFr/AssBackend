# The jar doesn't depend on the CPU, so it's built on the build machine's platform
# (fast even when the image targets arm64), and only the runtime stage is arm64.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
COPY resources resources
RUN ./gradlew --no-daemon -q shadowJar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 ass
COPY --from=build /src/build/libs/anonymoussimplestats-0.0.1-all.jar /app/ass.jar
USER ass
ENV KTOR_ENV=prod PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && head -1 <&3 | grep -q " 200 "'
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "-Xss512k", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/ass.jar"]
