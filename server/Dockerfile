FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon buildFatJar

FROM docker.io/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/herewego-server.jar app.jar
VOLUME /app/data
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
