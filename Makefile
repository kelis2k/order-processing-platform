SHELL := /bin/bash
COMPOSE := docker compose

.DEFAULT_GOAL := help

.PHONY: help dev-up dev-down dev-logs dev-reset k3d-up inject-secrets

help: ## Список доступных команд
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

dev-up: ## Поднять весь стек локально (detached)
	$(COMPOSE) up -d

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
