#!/usr/bin/env bash
# Levanta todo el entorno con un solo comando: Oracle, RabbitMQ, la pasarela simulada, el servicio
# y la consola de la prueba, como contenedores que se reinician solos. Idempotente.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "1/3 Compilando el servicio..."
ls target/payment-service-*.jar >/dev/null 2>&1 || ./mvnw -q -DskipTests package

echo "2/3 Levantando contenedores (Oracle, RabbitMQ, pasarela, servicio y consola)..."
docker compose --profile completo up -d --build

printf "3/3 Esperando a que todo responda (la primera vez Oracle tarda ~1 min)"
until curl -s localhost:8090/api/health 2>/dev/null | grep -q '"service": true'; do printf "."; sleep 3; done
echo " listo"

cat <<TXT

  Consola de la prueba: puerto 8090 (en Codespaces, pestaña PORTS).
  Recorre cada escenario con botones, paso a paso.

  O desde esta terminal:
    ./scripts/demo.sh               flujo completo, duplicados y fuera de orden
    ./scripts/demo-timeout.sh       timeout → UNKNOWN → conciliación
    ./scripts/demo-liquidacion.sh   conciliación diaria contra la liquidación

  RabbitMQ: puerto 15672 (guest/guest) · Logs: docker compose logs -f servicio
TXT
if [ -n "${CODESPACE_NAME:-}" ]; then
  echo
  echo "  Consola de la prueba: https://${CODESPACE_NAME}-8090.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
fi
