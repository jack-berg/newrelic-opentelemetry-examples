FROM eclipse-temurin:17-jre

ADD application/build/libs/application.jar /app.jar
ADD config-extension/build/libs/config-extension.jar /extension.jar
ADD application/build/otel/opentelemetry-javaagent.jar /javaagent.jar

ENTRYPOINT java -javaagent:javaagent.jar -Dotel.javaagent.extensions=extension.jar -jar app.jar