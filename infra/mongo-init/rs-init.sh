#!/usr/bin/env bash
# Инициализация MongoDB replica set rs0.
# Запускается одноразовым контейнером mongo-init после того, как mongo healthy.

set -euo pipefail

HOST="mongo:27017"
MAX_ATTEMPTS=30
ATTEMPT=0

echo "[rs-init] Ожидаю готовности $HOST..."
until mongosh --host "$HOST" --quiet --eval "db.adminCommand('ping').ok" 2>/dev/null | grep -q "^1$"; do
  ATTEMPT=$((ATTEMPT + 1))
  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "[rs-init] ОШИБКА: $HOST не ответил за $MAX_ATTEMPTS попыток" >&2
    exit 1
  fi
  echo "[rs-init]   попытка $ATTEMPT/$MAX_ATTEMPTS, жду 2с..."
  sleep 2
done

echo "[rs-init] MongoDB доступна. Инициализирую replica set rs0..."
mongosh --host "$HOST" --quiet <<'EOF'
try {
  rs.initiate({
    _id: "rs0",
    members: [{ _id: 0, host: "mongo:27017" }]
  });
  print("[rs-init] rs.initiate() выполнен, жду election...");
  sleep(3000);
} catch(e) {
  if (e.codeName === "AlreadyInitialized") {
    print("[rs-init] Replica set уже инициализирован, пропускаю.");
  } else {
    throw e;
  }
}
EOF

echo "[rs-init] Жду первичного узла..."
mongosh --host "$HOST" --quiet <<'EOF'
let attempts = 0;
while (attempts < 20) {
  try {
    if (db.hello().isWritablePrimary) {
      print("[rs-init] Узел стал Primary.");
      break;
    }
  } catch(e) {}
  sleep(1000);
  attempts++;
  if (attempts === 20) { throw new Error("[rs-init] Primary не выбран за 20 секунд"); }
}
EOF

echo "[rs-init] Создаю коллекции-заглушки (чтобы БД появились в списке)..."
mongosh --host "$HOST" --quiet <<'EOF'
db.getSiblingDB("product_db").createCollection("_init");
db.getSiblingDB("notification_db").createCollection("_init");
print("[rs-init] БД product_db и notification_db готовы.");
EOF

echo "[rs-init] Готово."
