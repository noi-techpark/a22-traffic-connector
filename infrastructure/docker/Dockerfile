FROM maven:3-jdk-8-alpine as base
COPY target/app.jar app.jar
CMD [ "java", "-jar", "app.jar", "follow"]
