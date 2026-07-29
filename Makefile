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


K3D_CLUSTER := orders
K8S_NAMESPACE := platform
TLS_SRC := .secrets/dev/tls

export NO_PROXY := localhost,127.0.0.1,0.0.0.0,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,.svc,.cluster.local
export no_proxy := $(NO_PROXY)

.DEFAULT_GOAL := help

.PHONY: help build-jars docker-images dev-up dev-down dev-logs dev-reset k3d-up k3d-down inject-secrets otel-agent tls-certs

help: ## Список доступных команд
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

dev-up: build-jars ## Поднять весь стек локально (detached). Пересобирает JAR и Docker-образы
	$(COMPOSE) up -d --build

dev-down: ## Остановить стек (данные в volumes сохраняются)
	$(COMPOSE) down

dev-logs: ## Следить за логами всех контейнеров
	$(COMPOSE) logs -f

dev-reset: ## Остановить стек и УДАЛИТЬ все volumes (полный сброс данных)
	$(COMPOSE) down -v

k3d-up: ## [9.3] Создать k3d-кластер и развернуть платформу
	@test -f $(TLS_SRC)/ca.crt || { echo "нет сертификатов — сначала make tls-certs"; exit 1; }
	@k3d cluster list $(K3D_CLUSTER) >/dev/null 2>&1 \
	  || k3d cluster create $(K3D_CLUSTER) --servers 1 --agents 0 -p "8087:80@loadbalancer" --wait
	k3d image import $(foreach s,$(SERVICES),$(s):latest) -c $(K3D_CLUSTER)
	kubectl create namespace $(K8S_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	kubectl create secret generic platform-tls -n $(K8S_NAMESPACE) \
	  --from-file=$(TLS_SRC)/ca.crt \
	  --from-file=$(TLS_SRC)/order-server.crt --from-file=$(TLS_SRC)/order-server.key \
	  --from-file=$(TLS_SRC)/order-client.crt --from-file=$(TLS_SRC)/order-client.key \
	  --from-file=$(TLS_SRC)/inventory-server.crt --from-file=$(TLS_SRC)/inventory-server.key \
	  --dry-run=client -o yaml | kubectl apply -f -
	helm dependency update helm/platform
	helm upgrade --install platform helm/platform -n $(K8S_NAMESPACE)
	@echo "ждём готовности подов (первые минуты рестарты — это нормально)"
	-@for r in $$(kubectl get deploy,statefulset -n $(K8S_NAMESPACE) -o name); do \
	  kubectl rollout status $$r -n $(K8S_NAMESPACE) --timeout=600s || true; \
	done
	@kubectl get pods -n $(K8S_NAMESPACE)
	@echo "gateway: kubectl port-forward -n $(K8S_NAMESPACE) svc/api-gateway 8087:8087"

k3d-down: ## [9.3] Удалить k3d-кластер целиком
	k3d cluster delete $(K3D_CLUSTER)

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
