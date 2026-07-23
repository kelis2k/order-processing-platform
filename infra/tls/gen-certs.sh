#!/usr/bin/env bash
# Генерация dev-PKI для межсервисного gRPC.
#
#   ./infra/tls/gen-certs.sh            # сгенерировать, если ещё нет
#   ./infra/tls/gen-certs.sh --force    # перевыпустить всё с нуля
#
# Выпускается self-signed CA и три листовых сертификата:
#   inventory-server — TLS-сервер inventory-service (:9090)
#   order-server     — TLS-сервер order-service, внешний gRPC-entrypoint (:9091)
#   order-client     — клиентский сертификат order-service для mTLS в inventory
#
# Ключи — PKCS#8 (openssl genpkey): netty/gRPC читает только этот формат,
# PKCS#1 из `openssl genrsa` он не примет.
#
# Вывод — в .secrets/dev/tls (каталог в .gitignore; на этапе 9 поедет в sealed-secrets).
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT="${TLS_DIR:-.secrets/dev/tls}"
DAYS_CA=3650
DAYS_LEAF=825

if [ -f "$OUT/ca.crt" ] && [ "${1:-}" != "--force" ]; then
  echo "✔ PKI уже есть в $OUT (перевыпуск: $0 --force)"
  exit 0
fi

mkdir -p "$OUT"
rm -f "$OUT"/*.crt "$OUT"/*.key "$OUT"/*.srl

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$OUT/ca.key"
openssl req -x509 -new -key "$OUT/ca.key" -sha256 -days "$DAYS_CA" \
  -subj "/CN=Order Platform Dev CA/O=order-platform" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out "$OUT/ca.crt"


gen_leaf() {
  local name="$1" cn="$2" san="$3" eku="$4"

  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT/$name.key"
  openssl req -new -key "$OUT/$name.key" -subj "/CN=$cn/O=order-platform" -out "$OUT/$name.csr"


cat > "$OUT/$name.ext" <<EOF
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = $eku
subjectAltName = $san
EOF

  openssl x509 -req -in "$OUT/$name.csr" \
    -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
    -days "$DAYS_LEAF" -sha256 -extfile "$OUT/$name.ext" \
    -out "$OUT/$name.crt"

  rm -f "$OUT/$name.csr" "$OUT/$name.ext"
  echo "  ✔ $name ($cn)"
}

gen_leaf inventory-server inventory-service \
  "DNS:localhost,DNS:inventory-service,DNS:inventory-service.default.svc.cluster.local,IP:127.0.0.1" \
  "serverAuth"

gen_leaf order-server order-service \
  "DNS:localhost,DNS:order-service,DNS:order-service.default.svc.cluster.local,IP:127.0.0.1" \
  "serverAuth"

gen_leaf order-client order-service \
  "DNS:order-service" \
  "clientAuth"

chmod 600 "$OUT"/*.key
rm -f "$OUT"/*.srl

echo "готово: $OUT (в .gitignore — приватные ключи в репозиторий не попадут)"




