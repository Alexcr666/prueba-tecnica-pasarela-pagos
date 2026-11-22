#!/usr/bin/env bash
# Levanta todo el entorno con un solo comando: Oracle, RabbitMQ, pasarela simulada,
# el servicio (Spring Boot) y el visor de eventos. Idempotente: si algo ya corre, lo deja.
set -euo pipefail
cd "$(dirname "$0")/.."
export WEBHOOK_SECRETS=${WEBHOOK_SECRETS:-dev-secret}
PORT=${SERVER_PORT:-8080}
LOGS=${LOGS:-/tmp}

echo "1/4 Infraestructura (Oracle, RabbitMQ, pasarela simulada)..."
docker compose up -d
printf "    esperando a Oracle"
until docker compose ps oracle --format '{{.Status}}' | grep -q healthy; do printf "."; sleep 5; done
echo " listo"

echo "2/4 Servicio de pagos en :$PORT..."
if ! curl -s "localhost:$PORT/actuator/health" | grep -q UP; then
  SERVER_PORT=$PORT nohup ./mvnw -q spring-boot:run > "$LOGS/servicio.log" 2>&1 &
  printf "    arrancando"
  until curl -s "localhost:$PORT/actuator/health" | grep -q UP; do printf "."; sleep 3; done
  echo " listo"
fi

echo "3/4 Visor de eventos en :8090..."
pgrep -f visor-eventos.py >/dev/null || nohup python3 scripts/visor-eventos.py > "$LOGS/visor.log" 2>&1 &
sleep 2

echo "4/4 Todo arriba."
cat <<TXT

  Prueba ahora (en esta terminal):
    ./scripts/demo.sh               flujo completo, duplicados y fuera de orden
    ./scripts/demo-timeout.sh       timeout → UNKNOWN → conciliación
    ./scripts/demo-liquidacion.sh   conciliación diaria contra la liquidación

  Visor de eventos:  puerto 8090      RabbitMQ: puerto 15672 (guest/guest)
  Logs del servicio: $LOGS/servicio.log
TXT
