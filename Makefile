SHELL := /bin/bash
COMPOSE := docker compose

.DEFAULT_GOAL := help

.PHONY: help build-jars dev-up dev-down dev-logs dev-reset k3d-up inject-secrets

help: ## Список доступных команд
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

build-jars: ## Собрать bootJar'ы сервисов на хосте (зависимости через хостовый Gradle-кэш)
	./gradlew :services:product-service:bootJar

dev-up: build-jars ## Поднять весь стек локально (detached). Пересобирает JAR и Docker-образы
	$(COMPOSE) up -d --build

dev-down: ## Остановить стек (данные в volumes сохраняются)
	$(COMPOSE) down

dev-logs: ## Следить за логами всех контейнеров
	$(COMPOSE) logs -f

dev-reset: ## Остановить стек и УДАЛИТЬ все volumes (полный сброс данных)
	$(COMPOSE) down -v

k3d-up: ## [Этап 9] Создать k3d-кластер и задеплоить Helm umbrella chart
	@echo "k3d-up: будет реализован на этапе 9"

inject-secrets: ## [Этап 9] Зашифровать .secrets/dev и применить в k3d
	@echo "inject-secrets: будет реализован на этапе 9"
OTEL_AGENT_VERSION := 2.29.0
otel-agent: ## [Этап 7.4] Скачать OpenTelemetry Java agent (версия зафиксирована ради воспроизводимости)
	mkdir -p infra/otel
	curl -sL -o infra/otel/opentelemetry-javaagent.jar \
		https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$(OTEL_AGENT_VERSION)/opentelemetry-javaagent.jar
