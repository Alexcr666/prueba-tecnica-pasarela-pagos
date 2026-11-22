#!/usr/bin/env bash
# Escenario de resultado incierto: la pasarela tarda más que el timeout de lectura (10 s).
# El pago queda UNKNOWN (nunca se reintenta a ciegas) y la conciliación lo resuelve consultando.
# Requisitos: docker compose up -d  +  WEBHOOK_SECRETS=dev-secret ./mvnw spring-boot:run
set -euo pipefail

API=${API:-http://localhost:8080}
PASARELA=${PASARELA:-http://localhost:8089}

json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
paso() { printf '\n\033[1;34m== %s\033[0m\n' "$1"; }
ok()   { printf '\033[0;32m   ✔ %s\033[0m\n' "$1"; }

limpiar() { curl -s -X POST "$PASARELA/__admin/mappings/reset" >/dev/null || true; }
trap limpiar EXIT

paso "1. Hacer que la pasarela tarde 15 s en crear pagos (timeout del servicio: 10 s)"
curl -s -X POST "$PASARELA/__admin/mappings" -H 'Content-Type: application/json' -d '{
  "priority": 1,
  "request":  {"method": "POST", "url": "/v1/payments"},
  "response": {"status": 201, "fixedDelayMilliseconds": 15000,
               "headers": {"Content-Type": "application/json"},
               "body": "{\"id\":\"pay-lento\",\"status\":\"PENDING\"}"}
}' >/dev/null
ok "Pasarela lenta activada"

paso "2. Crear una orden"
KEY="timeout-$(date +%s)"
ORDER_ID=$(curl -s -X POST "$API/orders" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"customerId":"cliente-demo","amountMinor":150000,"currency":"COP"}' | json "['id']")
ok "Orden $ORDER_ID"

paso "3. Pagar: el servicio espera 10 s y corta (read timeout)"
INICIO=$(date +%s)
CODE=$(curl -s -o /tmp/demo_to.json -w '%{http_code}' -X POST "$API/orders/$ORDER_ID/payments" -H "Idempotency-Key: pago-$KEY")
echo "   HTTP $CODE en $(( $(date +%s) - INICIO )) s → $(cat /tmp/demo_to.json)"
ATTEMPT_ID=$(json "['attemptId']" < /tmp/demo_to.json)
[ "$(json "['status']" < /tmp/demo_to.json)" = "UNKNOWN" ] && ok "UNKNOWN: no sabemos si cobró, así que NO se reintenta"

paso "4. Nuevo clic en «pagar» mientras está UNKNOWN → bloqueado"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/orders/$ORDER_ID/payments" -H "Idempotency-Key: otro-$KEY")
[ "$CODE" = "409" ] && ok "HTTP 409: no hay doble cobro"
echo "   Peticiones POST que recibió la pasarela para este intento: $(curl -s "$PASARELA/__admin/requests" | python3 -c "
import sys,json; print(sum(1 for r in json.load(sys.stdin)['requests'] if r['request']['method']=='POST' and '$ATTEMPT_ID' in r['request'].get('body','')))")"

paso "5. Quitar la demora (la pasarela vuelve a responder normal)"
limpiar
ok "La consulta GET /v1/payments?reference=… responde APPROVED"

paso "6. Esperar a la conciliación (corre cada 60 s; primera consulta a los 30 s)"
for i in $(seq 1 30); do
  P=$(curl -s "$API/payments/$ATTEMPT_ID" | json "['status']")
  printf '\r   %3s s  pago=%-9s' "$((i*5))" "$P"
  [ "$P" = "APPROVED" ] && break
  sleep 5
done
echo
O=$(curl -s "$API/orders/$ORDER_ID" | json "['status']")
[ "$P" = "APPROVED" ] && ok "Pago APPROVED por conciliación (fuente: RECONCILIATION)"
[ "$O" = "PAID" ] && ok "Orden PAID"

printf '\n\033[1;32mTimeout resuelto sin intervención manual.\033[0m Historial en Oracle:\n'
echo "   SELECT from_status, to_status, source, occurred_at FROM payment_status_history WHERE attempt_id = '$ATTEMPT_ID' ORDER BY id;"
