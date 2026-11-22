#!/usr/bin/env bash
# Levanta todo el entorno con un solo comando: Oracle, RabbitMQ, pasarela simulada,
# el servicio (Spring Boot) y la consola de la prueba. Idempotente: si algo ya corre, lo deja.
set -euo pipefail
cd "$(dirname "$0")/.."
export WEBHOOK_SECRETS=${WEBHOOK_SECRETS:-dev-secret}
PORT=${SERVER_PORT:-8080}
LOGS=${LOGS:-/tmp}
# Los procesos en segundo plano deben sobrevivir a este script (en Codespaces la terminal
# del postAttachCommand se cierra al terminar). setsid no existe en macOS: ahí basta nohup.
BG=(nohup); command -v setsid >/dev/null && BG=(setsid -f nohup)

echo "1/4 Infraestructura (Oracle, RabbitMQ, pasarela simulada)..."
docker compose up -d
printf "    esperando a Oracle"
until docker compose ps oracle --format '{{.Status}}' | grep -q healthy; do printf "."; sleep 5; done
echo " listo"

echo "2/4 Servicio de pagos en :$PORT..."
if ! curl -s "localhost:$PORT/actuator/health" | grep -q UP; then
  SERVER_PORT=$PORT "${BG[@]}" ./mvnw -q spring-boot:run > "$LOGS/servicio.log" 2>&1 < /dev/null &
  printf "    arrancando"
  until curl -s "localhost:$PORT/actuator/health" | grep -q UP; do printf "."; sleep 3; done
  echo " listo"
fi

echo "3/4 Consola de la prueba en :8090..."
pgrep -f scripts/consola.py >/dev/null || API_URL="http://localhost:$PORT" "${BG[@]}" python3 scripts/consola.py > "$LOGS/consola.log" 2>&1 < /dev/null &
sleep 2

echo "4/4 Todo arriba."
cat <<TXT

  ▶ Abre la CONSOLA DE LA PRUEBA en el puerto 8090 (en Codespaces: pestaña PORTS → globo del 8090).
    Recorre cada escenario con botones, paso a paso.

  O desde esta terminal:
    ./scripts/demo.sh               flujo completo, duplicados y fuera de orden
    ./scripts/demo-timeout.sh       timeout → UNKNOWN → conciliación
    ./scripts/demo-liquidacion.sh   conciliación diaria contra la liquidación

  RabbitMQ: puerto 15672 (guest/guest)
  Logs del servicio: $LOGS/servicio.log
TXT
if [ -n "${CODESPACE_NAME:-}" ]; then
  echo
  echo "  Consola de la prueba: https://${CODESPACE_NAME}-8090.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
fi
