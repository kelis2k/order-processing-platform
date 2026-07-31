#!/usr/bin/env bash
set -euo pipefail

NS="${1:-platform}"
SRC="${SECRETS_SRC:-.secrets/dev}"
TLS="$SRC/tls"

test -f "$TLS/ca.crt" \
  || { echo "нет $TLS/ca.crt — сначала ./infra/tls/gen-certs.sh"; exit 1; }
test -f "$SRC/jwt/jwt-private.pem" \
  || { echo "нет $SRC/jwt/jwt-private.pem — сначала ./infra/secrets/gen-dev-secrets.sh"; exit 1; }

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic platform-tls -n "$NS" \
  --from-file="$TLS/ca.crt" \
  --from-file="$TLS/order-server.crt"     --from-file="$TLS/order-server.key" \
  --from-file="$TLS/order-client.crt"     --from-file="$TLS/order-client.key" \
  --from-file="$TLS/inventory-server.crt" --from-file="$TLS/inventory-server.key" \
  --from-file="$TLS/product-server.crt"   --from-file="$TLS/product-server.key" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic auth-jwt -n "$NS" \
  --from-file="$SRC/jwt/jwt-private.pem" --from-file="$SRC/jwt/jwt-public.pem" \
  --dry-run=client -o yaml | kubectl apply -f -

for s in auth-service user-service order-service inventory-service; do
  kubectl create secret generic "$s-db" -n "$NS" \
    --from-env-file="$SRC/db/$s.env" \
    --dry-run=client -o yaml | kubectl apply -f -
done

kubectl get secrets -n "$NS"
