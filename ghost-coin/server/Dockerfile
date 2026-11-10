# À construire depuis le dossier ghost-coin :  docker build -f server/Dockerfile -t ghost-coin-server .
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY core core
COPY server server
RUN sed -i 's/, ":app"//' settings.gradle.kts && sed -i '/com.android.application/d;/kotlin.android/d;/plugin.compose/d' build.gradle.kts && gradle :server:fatJar --no-daemon

FROM eclipse-temurin:21-jre
COPY --from=build /src/server/build/libs/server-all.jar /app/server.jar
ENV PORT=8080 DATA_DIR=/data
VOLUME /data
EXPOSE 8080
CMD ["java", "-jar", "/app/server.jar"]
