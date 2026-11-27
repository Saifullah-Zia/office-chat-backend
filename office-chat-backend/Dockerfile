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
# pg_dump for the nightly database backup. It must be the same major version as the database (18),
# which the distribution doesn't ship, so it comes from the PostgreSQL project's own package list.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && install -d /usr/share/postgresql-common/pgdg \
 && curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc \
 && . /etc/os-release \
 && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client-18 \
 && apt-get purge -y curl && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/app.jar app.jar
# Uploads, profile photos and push keys live on the Railway volume mounted here.
RUN mkdir -p /data
# Memory: Railway bills what the app actually uses, and Java otherwise grows into most of the
# container's (large) limit. 384 MB of heap is plenty for 20–40 people; raise -Xmx here, or override
# JAVA_TOOL_OPTIONS in Railway's Variables, if the team grows a lot. Railway sets PORT.
ENV JAVA_TOOL_OPTIONS="-Xmx384m -Xss512k -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
