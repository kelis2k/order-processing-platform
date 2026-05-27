# =============================================================================
# product-service runtime image.
# JAR собирается на хосте (`./gradlew :services:product-service:bootJar`) и
# копируется в образ — это обходит сетевую нестабильность WSL2-Docker ↔
# packages.confluent.io. Makefile target `dev-up` собирает JAR автоматически.
# =============================================================================
FROM eclipse-temurin:21-jre-alpine

# Непривилегированный пользователь (security best-practice)
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

WORKDIR /app
COPY services/product-service/build/libs/product-service.jar app.jar

USER appuser
EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
