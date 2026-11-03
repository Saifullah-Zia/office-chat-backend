# ---- Build ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar app.jar

# ---- Run ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
# Uploads, profile photos and push keys live on the Railway volume mounted here.
RUN mkdir -p /data
# Memory: Railway bills what the app actually uses, and Java otherwise grows into most of the
# container's (large) limit. 384 MB of heap is plenty for 20–40 people; raise -Xmx here, or override
# JAVA_TOOL_OPTIONS in Railway's Variables, if the team grows a lot. Railway sets PORT.
ENV JAVA_TOOL_OPTIONS="-Xmx384m -Xss512k -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
