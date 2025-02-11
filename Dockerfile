FROM docker.io/maven:3.9-eclipse-temurin-17 AS build
RUN mkdir -p /workspace
WORKDIR /workspace
COPY pom.xml /workspace
COPY src /workspace/src
RUN mvn -B -f pom.xml clean package -DskipTests

FROM docker.io/openjdk:17-alpine

COPY --from=build /workspace/target/jmx2graphite-*-javaagent.jar /jmx2graphite.jar
COPY application.conf /application.conf
# Default Start
CMD [ "java", "-cp", "jmx2graphite.jar", "io.logz.jmx2graphite.Jmx2GraphiteJolokia", "application.conf" ]
