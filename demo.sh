#!/usr/bin/env bash
#
# Полный жизненный цикл заказа по внешнему API — без ручных вмешательств.
#
#   ./demo.sh                 проверки, итог, код возврата 0/1
#   VERBOSE=1 ./demo.sh       + тела запросов и ответов
#
# Переменные: BASE_URL (http://localhost:8087), MAILHOG_URL (http://localhost:8025),
#             ADMIN_EMAIL (admin@orders.local), PASSWORD (Password1!),
#             JAEGER_URL, GRAFANA_URL, KAFKA_UI_URL, SWAGGER_URL — адреса в блоке «куда смотреть»

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8087}"
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@orders.local}"
PASSWORD="${PASSWORD:-Password1!}"
VERBOSE="${VERBOSE:-}"

JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
KAFKA_UI_URL="${KAFKA_UI_URL:-http://localhost:8080}"
SWAGGER_URL="${SWAGGER_URL:-http://localhost:8083/swagger-ui.html}"

RUN_ID="$(date +%s)"
BUYER_EMAIL="buyer-${RUN_ID}@example.com"
STRANGER_EMAIL="stranger-${RUN_ID}@example.com"

RESP="$(mktemp)"
trap 'rm -f "$RESP"' EXIT

PASSED=0
FAILED=0

G='\033[0;32m'; R='\033[0;31m'; Y='\033[0;33m'; N='\033[0m'

step()  { printf "\n${Y}▸ %s${N}\n" "$*"; }
pass()  { PASSED=$((PASSED + 1)); printf "  ${G}✔${N} %s\n" "$*"; }
fail()  { FAILED=$((FAILED + 1)); printf "  ${R}✘${N} %s\n" "$*"; printf "    ответ: %s\n" "$(head -c 400 "$RESP")"; }
info()  { [[ -n "$VERBOSE" ]] && printf "    %s\n" "$*"; return 0; }

req() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  local args=(-s -o "$RESP" -w '%{http_code}' --noproxy '*' -X "$method" "${BASE_URL}${path}")
  args+=(-H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer ${token}")
  [[ -n "$body" ]] && args+=(-d "$body")

  local code=""
  for _ in 1 2 3 4 5 6 7 8; do
    code="$(curl "${args[@]}")"
    [[ "$code" == "429" ]] || break
    sleep 1.5
  done
  info "$method $path -> $code"
  [[ -n "$VERBOSE" ]] && head -c 300 "$RESP" | sed 's/^/    /'
  echo "$code"
}

expect() {
  local desc="$1" want="$2" got="$3"
  if [[ "$got" == "$want" ]]; then pass "$desc (HTTP $got)"; return 0; fi
  fail "$desc — ожидали HTTP $want, получили $got"
  return 1
}

jwt_claim() {
  local payload="${1#*.}"; payload="${payload%%.*}"
  local pad=$((${#payload} % 4))
  [[ $pad -ne 0 ]] && payload="${payload}$(printf '=%.0s' $(seq $((4 - pad))))"
  printf '%s' "$payload" | tr '_-' '/+' | base64 -d 2>/dev/null | jq -r "$2"
}

wait_for() {
  local desc="$1" timeout="$2"; shift 2
  local deadline=$((SECONDS + timeout))
  while [[ $SECONDS -lt $deadline ]]; do
    if "$@"; then pass "$desc"; return 0; fi
    sleep 2
  done
  fail "$desc — не сошлось за ${timeout} с"
  return 1
}

confirmation_link() {
  local email="$1"
  curl -s --noproxy '*' "${MAILHOG_URL}/api/v2/messages?limit=200" \
    | jq -r --arg e "$email" '
        .items
        | map(select(.Content.Headers.To[]? | contains($e)))
        | map(.Content.Body) | .[]' \
    | sed ':a;N;$!ba;s/=\r\?\n//g' \
    | sed 's/=3D/=/g' \
    | grep -oE '/auth/confirm\?token=[A-Za-z0-9._-]+' \
    | head -1
}

mails_to() {
  curl -s --noproxy '*' "${MAILHOG_URL}/api/v2/messages?limit=200" \
    | jq --arg e "$1" '[.items[] | select(.Content.Headers.To[]? | contains($e))] | length'
}

register_and_confirm() {
  local email="$1" username="$2"
  local code
  code="$(req POST /auth/register "{\"email\":\"$email\",\"username\":\"$username\",\"password\":\"$PASSWORD\"}")"
  [[ "$code" == "201" || "$code" == "409" ]] || { fail "регистрация $email — HTTP $code"; return 1; }
  pass "регистрация $email (HTTP $code)"

  if [[ "$code" == "409" ]]; then
    if [[ "$(req POST /auth/login "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}")" == "200" ]]; then
      pass "$email уже зарегистрирован и подтверждён"
      return 0
    fi
  fi

  local link=""
  local deadline=$((SECONDS + 60))
  while [[ $SECONDS -lt $deadline ]]; do
    link="$(confirmation_link "$email")"
    [[ -n "$link" ]] && break
    sleep 2
  done
  if [[ -z "$link" ]]; then
    [[ "$code" == "409" ]] \
      && fail "$email уже существует, но пароль не подходит — сбрось данные (make dev-reset) или задай PASSWORD" \
      || fail "письмо со ссылкой подтверждения для $email не пришло"
    return 1
  fi
  pass "письмо со ссылкой подтверждения доставлено"

  code="$(req GET "$link")"
  expect "подтверждение почты $email" 200 "$code"
}

login() {
  local email="$1"
  local code
  code="$(req POST /auth/login "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}")"
  [[ "$code" == "200" ]] || return 1
  jq -r '.accessToken' "$RESP"
}

printf "${Y}=== Order Processing Platform — сквозной сценарий ===${N}\n"
printf "gateway: %s | mailhog: %s\n" "$BASE_URL" "$MAILHOG_URL"

step "1. Платформа отвечает"
expect "gateway жив" 200 "$(req GET /actuator/health)"

step "2. Регистрация и подтверждение почты"
code="$(req POST /auth/register "{\"email\":\"$BUYER_EMAIL\",\"username\":\"buyer${RUN_ID}\",\"password\":\"$PASSWORD\"}")"
expect "регистрация покупателя" 201 "$code"
expect "логин до подтверждения запрещён" 403 "$(req POST /auth/login "{\"email\":\"$BUYER_EMAIL\",\"password\":\"$PASSWORD\"}")"

link=""
deadline=$((SECONDS + 60))
while [[ $SECONDS -lt $deadline ]]; do
  link="$(confirmation_link "$BUYER_EMAIL")"
  [[ -n "$link" ]] && break
  sleep 2
done
if [[ -n "$link" ]]; then
  pass "письмо со ссылкой подтверждения доставлено"
  expect "подтверждение по ссылке из письма" 200 "$(req GET "$link")"
else
  fail "письмо со ссылкой подтверждения не пришло"
fi

register_and_confirm "$ADMIN_EMAIL" "admin"
register_and_confirm "$STRANGER_EMAIL" "stranger${RUN_ID}"

step "3. Токены"
BUYER_TOKEN="$(login "$BUYER_EMAIL")"
[[ -n "${BUYER_TOKEN:-}" && "$BUYER_TOKEN" != "null" ]] \
  && pass "логин покупателя, роль $(jwt_claim "$BUYER_TOKEN" '.role')" \
  || fail "логин покупателя"

STRANGER_TOKEN="$(login "$STRANGER_EMAIL")"
[[ -n "${STRANGER_TOKEN:-}" && "$STRANGER_TOKEN" != "null" ]] \
  && pass "логин постороннего" || fail "логин постороннего"

admin_has_role() {
  ADMIN_TOKEN="$(login "$ADMIN_EMAIL")" || return 1
  [[ "$(jwt_claim "$ADMIN_TOKEN" '.role')" == "ROLE_ADMIN" ]]
}
wait_for "роль ROLE_ADMIN доехала до auth (user.created → user.role-changed)" 90 admin_has_role

step "4. Каталог"
expect "создание товара без токена запрещено" 401 \
  "$(req POST /products '{"name":"Demo","category":"demo","price":25.00,"published":true}')"
expect "создание товара покупателем запрещено" 403 \
  "$(req POST /products '{"name":"Demo","category":"demo","price":25.00,"published":true}' "$BUYER_TOKEN")"

code="$(req POST /products "{\"name\":\"Demo ${RUN_ID}\",\"description\":\"smoke\",\"category\":\"demo\",\"price\":25.00,\"published\":true}" "$ADMIN_TOKEN")"
expect "создание товара админом" 201 "$code"
PRODUCT_ID="$(jq -r '.id' "$RESP")"
info "productId=$PRODUCT_ID"

step "5. Склад"
expect "заведение остатка покупателем запрещено" 403 \
  "$(req PUT "/inventory/${PRODUCT_ID}" '{"available":50}' "$BUYER_TOKEN")"
expect "заведение остатка админом" 200 "$(req PUT "/inventory/${PRODUCT_ID}" '{"available":50}' "$ADMIN_TOKEN")"
expect "повторный вызов идемпотентен" 200 "$(req PUT "/inventory/${PRODUCT_ID}" '{"available":50}' "$ADMIN_TOKEN")"
[[ "$(jq -r '.available' "$RESP")" == "50" ]] && pass "остаток 50 после повтора" || fail "остаток после повтора"

step "6. Заказ"
code="$(req POST /orders "{\"items\":[{\"productId\":\"${PRODUCT_ID}\",\"quantity\":2}]}" "$BUYER_TOKEN")"
expect "создание заказа покупателем" 201 "$code"
ORDER_ID="$(jq -r '.id' "$RESP")"
ORDER_STATUS="$(jq -r '.status' "$RESP")"
ORDER_TOTAL="$(jq -r '.totalAmount' "$RESP")"
[[ "$ORDER_STATUS" == "NEW" ]] && pass "статус NEW" || fail "статус при создании: $ORDER_STATUS"
[[ "$(jq -r '.totalAmount == 50' "$RESP")" == "true" ]] \
  && pass "сумма посчитана каталогом: $ORDER_TOTAL (2 × 25.00)" \
  || fail "totalAmount=$ORDER_TOTAL, ожидали 50 (gRPC-каталог цен, ADR 0017)"

expect "чужой заказ не виден постороннему" 403 "$(req GET "/orders/${ORDER_ID}" '' "$STRANGER_TOKEN")"
expect "заказ без токена не виден" 401 "$(req GET "/orders/${ORDER_ID}")"

step "7. SAGA: резервирование через Kafka"
order_reserved() {
  req GET "/orders/${ORDER_ID}" '' "$BUYER_TOKEN" >/dev/null
  [[ "$(jq -r '.status' "$RESP")" == "RESERVED" ]]
}
wait_for "заказ перешёл в RESERVED (order.created → inventory.reserved)" 120 order_reserved

req GET "/inventory/${PRODUCT_ID}" '' "$ADMIN_TOKEN" >/dev/null
AVAILABLE="$(jq -r '.available' "$RESP")"
RESERVED="$(jq -r '.reserved' "$RESP")"
[[ "$AVAILABLE" == "48" && "$RESERVED" == "2" ]] \
  && pass "склад: доступно 48, зарезервировано 2" \
  || fail "склад: доступно $AVAILABLE, зарезервировано $RESERVED (ожидали 48/2)"

step "8. Полный жизненный цикл заказа"
expect "оплата покупателем" 200 "$(req POST "/orders/${ORDER_ID}/pay" '' "$BUYER_TOKEN")"
[[ "$(jq -r '.status' "$RESP")" == "PAID" ]] && pass "статус PAID" || fail "статус после оплаты: $(jq -r '.status' "$RESP")"
expect "повторная оплата отклонена" 409 "$(req POST "/orders/${ORDER_ID}/pay" '' "$BUYER_TOKEN")"
expect "отгрузка покупателем запрещена" 403 "$(req POST "/orders/${ORDER_ID}/ship" '' "$BUYER_TOKEN")"

STRANGER_ID="$(jwt_claim "$STRANGER_TOKEN" '.sub')"
expect "админ назначает роль MANAGER" 200 \
  "$(req PUT "/users/${STRANGER_ID}/role" '{"role":"ROLE_MANAGER"}' "$ADMIN_TOKEN")"

manager_has_role() {
  MANAGER_TOKEN="$(login "$STRANGER_EMAIL")" || return 1
  [[ "$(jwt_claim "$MANAGER_TOKEN" '.role')" == "ROLE_MANAGER" ]]
}
wait_for "роль ROLE_MANAGER доехала до auth (user.role-changed)" 90 manager_has_role

expect "отгрузка менеджером" 200 "$(req POST "/orders/${ORDER_ID}/ship" '' "$MANAGER_TOKEN")"
[[ "$(jq -r '.status' "$RESP")" == "SHIPPED" ]] && pass "статус SHIPPED" || fail "статус после отгрузки"

shipped_stock_committed() {
  req GET "/inventory/${PRODUCT_ID}" '' "$ADMIN_TOKEN" >/dev/null
  [[ "$(jq -r '.available' "$RESP")" == "48" && "$(jq -r '.reserved' "$RESP")" == "0" ]]
}
wait_for "склад списал резерв: доступно 48, зарезервировано 0" 60 shipped_stock_committed

expect "подтверждение получения покупателем" 200 "$(req POST "/orders/${ORDER_ID}/complete" '' "$BUYER_TOKEN")"
[[ "$(jq -r '.status' "$RESP")" == "COMPLETED" ]] && pass "статус COMPLETED" || fail "статус после подтверждения"

step "9. Уведомления"
buyer_got_mails() { [[ "$(mails_to "$BUYER_EMAIL")" -ge 5 ]]; }
wait_for "покупателю ушли письма (6-е может быть подавлено анти-спамом, ADR 0010)" 60 buyer_got_mails

printf "\n${Y}=== Что создано ===${N}\n"
printf "  покупатель  %s\n" "$BUYER_EMAIL"
printf "  товар       %s\n" "${PRODUCT_ID:-—}"
printf "  заказ       %s  (%s)\n" "${ORDER_ID:-—}" "$(jq -r '.status // "—"' "$RESP" 2>/dev/null)"

printf "\n${Y}=== Куда смотреть ===${N}\n"
printf "  письма покупателя   %s/#search?kind=to&query=%s\n" "$MAILHOG_URL" "$BUYER_EMAIL"
printf "  сквозной трейс      %s/search?service=api-gateway&operation=POST%%20%%2Forders&lookback=1h\n" "$JAEGER_URL"
printf "  метрики и дашборды  %s/dashboards\n" "$GRAFANA_URL"
printf "  топики и сообщения  %s\n" "$KAFKA_UI_URL"
printf "  API каталога        %s\n" "$SWAGGER_URL"
printf "  заказ по API        curl -H 'Authorization: Bearer <token>' %s/orders/%s\n" "$BASE_URL" "${ORDER_ID:-<id>}"

printf "\n${Y}=== Итог ===${N}\n"
printf "  пройдено: ${G}%d${N}\n" "$PASSED"
if [[ $FAILED -gt 0 ]]; then
  printf "  провалено: ${R}%d${N}\n" "$FAILED"
  exit 1
fi
printf "  ${G}все проверки пройдены${N}\n"
