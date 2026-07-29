#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT="${SECRETS_DIR:-.secrets/dev}"
FORCE="${1:-}"

mkdir -p "$OUT/jwt" "$OUT/db"

if [ -f "$OUT/jwt/jwt-private.pem" ] && [ "$FORCE" != "--force" ]; then
  echo "✔ JWT-ключи уже есть в $OUT/jwt (перевыпуск: $0 --force)"
else
  rm -f "$OUT/jwt/jwt-private.pem" "$OUT/jwt/jwt-public.pem"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT/jwt/jwt-private.pem" 2>/dev/null
  openssl pkey -in "$OUT/jwt/jwt-private.pem" -pubout -out "$OUT/jwt/jwt-public.pem"
  echo "✔ JWT-ключи выпущены: $OUT/jwt"
fi

write_db_env() {
  local svc="$1" user="$2" pass="$3"
  if [ -f "$OUT/db/$svc.env" ] && [ "$FORCE" != "--force" ]; then
    return
  fi
  cat > "$OUT/db/$svc.env" <<EOF
SPRING_DATASOURCE_USERNAME=$user
SPRING_DATASOURCE_PASSWORD=$pass
EOF
}

write_db_env auth-service auth auth
write_db_env user-service user_svc user_svc
write_db_env order-service order_svc order_svc
write_db_env inventory-service inventory_svc inventory_svc
echo "✔ Креды БД: $OUT/db (4 файла)"

echo
echo "Содержимое $OUT:"
find "$OUT" -type f | sort | sed 's/^/  /'
