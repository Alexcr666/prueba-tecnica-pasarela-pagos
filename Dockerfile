# Imagen del servicio. El jar se compila antes con ./mvnw package (en Codespaces lo hace postCreateCommand).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/payment-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "app.jar"]
