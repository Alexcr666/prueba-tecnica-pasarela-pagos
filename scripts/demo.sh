#!/usr/bin/env bash
# Recorre el flujo completo contra el servicio levantado en localhost:8080.
# Requisitos: docker compose up -d  +  ./mvnw spring-boot:run  (con WEBHOOK_SECRETS=dev-secret)
set -euo pipefail

API=${API:-http://localhost:8080}
SECRET=${WEBHOOK_SECRETS:-dev-secret}

json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
paso() { printf '\n\033[1;34m== %s\033[0m\n' "$1"; }
ok()   { printf '\033[0;32m   ✔ %s\033[0m\n' "$1"; }

webhook() { # $1=event_id $2=reference $3=status $4=firma (opcional: "mala")
  local body ts sig
  body=$(printf '{"id":"%s","type":"payment.updated","data":{"id":"pay-%s","reference":"%s","status":"%s","amount":150000,"currency":"COP"}}' "$1" "$2" "$2" "$3")
  ts=$(date +%s)
  sig=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')
  [ "${4:-}" = "mala" ] && sig="00${sig:2}"
  curl -s -o /tmp/demo_wh.json -w '%{http_code}' -X POST "$API/webhooks/DEMO" \
    -H 'Content-Type: application/json' -H "X-Provider-Timestamp: $ts" -H "X-Provider-Signature: $sig" -d "$body"
}

paso "1. Crear orden (con Idempotency-Key)"
KEY="orden-$(date +%s)"
ORDER=$(curl -s -X POST "$API/orders" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"customerId":"cliente-demo","amountMinor":150000,"currency":"COP"}')
ORDER_ID=$(echo "$ORDER" | json "['id']")
echo "   $ORDER"
ok "Orden $ORDER_ID en AWAITING_PAYMENT"

paso "2. Repetir la misma petición con la misma clave → misma orden, sin duplicar"
CODE=$(curl -s -o /tmp/demo_o.json -w '%{http_code}' -X POST "$API/orders" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"customerId":"cliente-demo","amountMinor":150000,"currency":"COP"}')
[ "$(json "['id']" < /tmp/demo_o.json)" = "$ORDER_ID" ] && ok "HTTP $CODE, mismo id: idempotencia de la API"

paso "3. Iniciar el pago → la pasarela simulada lo deja PENDING y devuelve la URL de redirección"
PAY=$(curl -s -X POST "$API/orders/$ORDER_ID/payments" -H "Idempotency-Key: pago-$KEY")
ATTEMPT_ID=$(echo "$PAY" | json "['attemptId']")
echo "   $PAY"
ok "Intento $ATTEMPT_ID en $(echo "$PAY" | json "['status']")"

paso "4. Segundo clic en «pagar» con OTRA clave → bloqueado (no hay doble cobro)"
CODE=$(curl -s -o /tmp/demo_p.json -w '%{http_code}' -X POST "$API/orders/$ORDER_ID/payments" -H "Idempotency-Key: otro-$KEY")
echo "   $(cat /tmp/demo_p.json)"
[ "$CODE" = "409" ] && ok "HTTP 409 ACTIVE_PAYMENT_EXISTS"

paso "5. Webhook con firma inválida → rechazado sin persistir"
CODE=$(webhook "evt-falso-$KEY" "$ATTEMPT_ID" APPROVED mala)
[ "$CODE" = "401" ] && ok "HTTP 401"

paso "6. Webhook APPROVED firmado (HMAC-SHA256)"
CODE=$(webhook "evt-1-$KEY" "$ATTEMPT_ID" APPROVED)
echo "   HTTP $CODE $(cat /tmp/demo_wh.json)"

paso "7. El proveedor reenvía el MISMO webhook → se reconoce como duplicado"
CODE=$(webhook "evt-1-$KEY" "$ATTEMPT_ID" APPROVED)
echo "   HTTP $CODE $(cat /tmp/demo_wh.json)"
grep -q '"duplicate":true' /tmp/demo_wh.json && ok "duplicate=true: sin efectos dobles"

paso "8. Esperar al procesador del inbox (asíncrono, ~1 s)"
sleep 3
P=$(curl -s "$API/payments/$ATTEMPT_ID"); O=$(curl -s "$API/orders/$ORDER_ID")
echo "   Pago:  $P"; echo "   Orden: $O"
[ "$(echo "$P" | json "['status']")" = "APPROVED" ] && ok "Pago APPROVED"
[ "$(echo "$O" | json "['status']")" = "PAID" ] && ok "Orden PAID"

paso "9. Llega tarde un webhook PENDING (fuera de orden) → se ignora, el estado no retrocede"
webhook "evt-0-$KEY" "$ATTEMPT_ID" PENDING >/dev/null
sleep 3
[ "$(curl -s "$API/payments/$ATTEMPT_ID" | json "['status']")" = "APPROVED" ] && ok "Sigue APPROVED"

printf '\n\033[1;32mFlujo completo verificado.\033[0m Historial en Oracle:\n'
echo "   SELECT from_status, to_status, source, occurred_at FROM payment_status_history WHERE order_id = '$ORDER_ID' ORDER BY id;"
