SHELL := /bin/bash
COMPOSE := docker compose
SERVICES := api-gateway auth-service user-service product-service \
            inventory-service order-service notification-service


PORT_api-gateway       := 8087
PORT_auth-service      := 8086
PORT_user-service      := 8082
PORT_product-service   := 8083
PORT_inventory-service := 8084
PORT_order-service     := 8085
PORT_notification-service := 8088


.DEFAULT_GOAL := help

.PHONY: help build-jars docker-images dev-up dev-down dev-logs dev-reset k3d-up inject-secrets otel-agent tls-certs

help: ## Список доступных команд
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

dev-up: build-jars ## Поднять весь стек локально (detached). Пересобирает JAR и Docker-образы
	$(COMPOSE) up -d --build

dev-down: ## Остановить стек (данные в volumes сохраняются)
	$(COMPOSE) down

dev-logs: ## Следить за логами всех контейнеров
	$(COMPOSE) logs -f

dev-reset: ## Остановить стек и УДАЛИТЬ все volumes (полный сброс данных)
	$(COMPOSE) down -v

k3d-up: ##  Создать k3d-кластер и задеплоить Helm umbrella chart
	@echo "k3d-up: будет реализован на этапе 9"

inject-secrets: ## Зашифровать .secrets/dev и применить в k3d
	@echo "inject-secrets: будет реализован на этапе 9"
OTEL_AGENT_VERSION := 2.29.0
otel-agent: ## Скачать OpenTelemetry Java agent (версия зафиксирована ради воспроизводимости)
	mkdir -p infra/otel
	curl -sL -o infra/otel/opentelemetry-javaagent.jar \
		https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$(OTEL_AGENT_VERSION)/opentelemetry-javaagent.jar

tls-certs: ## [8.3] Сгенерировать dev-PKI для gRPC TLS (CA + сертификаты сервисов)
	./infra/tls/gen-certs.sh

build-jars: ## Собрать bootJar'ы всех сервисов на хосте (зависимости через хостовый Gradle-кэш)
	./gradlew $(foreach s,$(SERVICES),:services:$(s):bootJar)

docker-images: build-jars ## [9.2] Собрать Docker-образы всех сервисов (для импорта в k3d)
	@for s in $(SERVICES); do \
	  echo "==> $$s"; \
	  docker build -f infra/docker/Dockerfile.service \
	    --build-arg SERVICE=$$s \
	    --build-arg PORT=$$(grep "^PORT_$$s " Makefile | awk '{print $$3}') \
	    -t $$s:latest . || exit 1; \
	done
