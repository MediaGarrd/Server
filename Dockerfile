FROM gradle:9.4.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle gradle :bootJar --no-daemon --stacktrace --info --warning-mode all

FROM eclipse-temurin:21.0.13_9-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50"

ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-Xms16m", \
  "-XX:MinHeapFreeRatio=10", \
  "-XX:MaxHeapFreeRatio=30", \
  "-XX:G1PeriodicGCInterval=60000", \
  "-XX:-G1PeriodicGCInvokesConcurrent", \
  "-XX:TrimNativeHeapInterval=10000", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]
