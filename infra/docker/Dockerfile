# One image per service from its boot jar. Build context: <module>/build/libs after ./gradlew bootJar.
FROM eclipse-temurin:21-jre AS layers
WORKDIR /layers
COPY *.jar /jars/
RUN java -Djarmode=tools -jar "$(ls /jars/*.jar | grep -v -- '-plain.jar$')" extract --layers --launcher --destination .

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER 10001
WORKDIR /app
COPY --from=layers /layers/dependencies/ ./
COPY --from=layers /layers/spring-boot-loader/ ./
COPY --from=layers /layers/snapshot-dependencies/ ./
COPY --from=layers /layers/application/ ./
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
