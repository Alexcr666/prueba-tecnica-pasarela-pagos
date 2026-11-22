# Cómo probar todo, paso a paso

Abre una terminal y ve a la carpeta del proyecto:

```bash
cd ~/Documents/prueba-gelsa-pagos
```

## 0. Encender Docker (una vez por sesión)

Con Docker Desktop basta con tenerlo abierto. En macOS con Colima:

```bash
colima start
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

`colima status` debe decir que está corriendo.

## 1. Pruebas automáticas (3 minutos)

```bash
./mvnw verify
```

Al final debe salir:

```
Tests run: 73, Failures: 0   ← unitarias
Tests run: 11, Failures: 0   ← integración contra Oracle real
BUILD SUCCESS
```

| Prueba | Qué demuestra |
| --- | --- |
| `PaymentStateMachineTest` | Las 49 combinaciones de estados cumplen la tabla 5.1 del diseño |
| `WebhookSignatureVerifierTest` | Firma válida, alterada, vencida, en rotación y timestamp absurdo |
| `HttpPaymentProviderAdapterTest` | Un timeout no se reintenta (`UNKNOWN`), un fallo de conexión sí, y la respuesta ante 4xx, 409 y 5xx |
| `PaymentFlowIT` | Webhook duplicado concurrente, eventos fuera de orden, timeout → conciliación, idempotencia |
| `SettlementIT` | Conciliación diaria en PL/SQL: 3 tipos de discrepancia y corrección automática |

Para correr una sola: `./mvnw verify -Dit.test=PaymentFlowIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`

## 2. Demo en vivo del flujo completo

**Terminal 1**: infraestructura (Oracle, RabbitMQ y una pasarela simulada).

```bash
docker compose up -d
docker compose ps        # espera a que oracle diga (healthy), ~1 min
```

**Terminal 1**: levanta el servicio.

```bash
WEBHOOK_SECRETS=dev-secret ./mvnw spring-boot:run
```

Espera a ver `Started PaymentServiceApplication`.

> **Si el puerto 8080 está ocupado** por otra aplicación, usa otro:
> `SERVER_PORT=8081 WEBHOOK_SECRETS=dev-secret ./mvnw spring-boot:run` y en la otra terminal `export API=http://localhost:8081` antes de correr los scripts.

**Terminal 2**: corre la demo.

```bash
cd ~/Documents/prueba-gelsa-pagos
./scripts/demo.sh
```

Recorre 9 pasos y marca cada uno como correcto:

1. Crear una orden.
2. Repetirla con la misma clave: se devuelve la misma orden (idempotencia).
3. Iniciar el pago: queda `PENDING` y devuelve la URL de redirección.
4. Segundo clic con otra clave: **409**, no hay doble cobro.
5. Webhook con firma falsa: **401**.
6. Webhook `APPROVED` firmado: se acepta.
7. El mismo webhook otra vez: se marca `duplicate=true` y no tiene efectos.
8. El pago queda `APPROVED` y la orden `PAID`.
9. Llega tarde un webhook `PENDING`: se ignora y el estado no retrocede.

## 3. Ver lo que pasó por dentro

**Historial en Oracle** (cambia el id por el que imprimió la demo):

```bash
docker compose exec oracle sqlplus payments/payments@FREEPDB1
```
```sql
SELECT from_status, to_status, source, occurred_at
  FROM payment_status_history WHERE order_id = '<ORDER_ID>' ORDER BY id;
SELECT provider_event_id, processing_status FROM provider_event ORDER BY id;
SELECT event_type, status FROM outbox_event ORDER BY created_at;
```

**Otras vistas:**
- **Consola de la prueba (recomendado):** `API_URL=http://localhost:8080 python3 scripts/consola.py` y abre http://localhost:8090. Recorre los escenarios con botones y explica cada paso; la sección *Eventos publicados* muestra el JSON de cada evento en árbol desplegable. `./scripts/iniciar.sh` la levanta junto con todo lo demás.
- **RabbitMQ:** http://localhost:15672 (usuario `guest`, clave `guest`). El exchange `payments.events` recibe los eventos. El servicio no declara colas (cada consumidor crea la suya), así que para verlos crea una cola de demo enlazada con `#`:
  ```bash
  curl -s -u guest:guest -X PUT localhost:15672/api/queues/%2F/demo.consumidor -H 'content-type: application/json' -d '{"durable":true,"arguments":{"x-queue-type":"quorum"}}'
  curl -s -u guest:guest -X POST localhost:15672/api/bindings/%2F/e/payments.events/q/demo.consumidor -H 'content-type: application/json' -d '{"routing_key":"#"}'
  ```
  Corre `./scripts/demo.sh` y en *Queues and Streams → demo.consumidor → Get messages* verás los 3 eventos.
- **Métricas:** http://localhost:8080/actuator/prometheus (busca `payments_`).
- **Logs del servicio:** están en JSON, con `correlation_id`.

## 4. Probar el timeout (resultado incierto)

```bash
./scripts/demo-timeout.sh
```

El script hace que la pasarela simulada tarde 15 s; el timeout de lectura del servicio es de 10 s. Luego:

1. El pago queda `UNKNOWN` con HTTP 202. Se envió **una sola** petición a la pasarela, sin reintento a ciegas.
2. Un segundo clic recibe **409**: no hay doble cobro.
3. El script quita la demora. En menos de 2 minutos la conciliación consulta `GET /v1/payments?reference=<attemptId>` y el pago pasa a `APPROVED` con fuente `RECONCILIATION`; la orden queda `PAID`.

Historial esperado en Oracle:

```
CREATED  → (nuevo)   API
CREATED  → UNKNOWN   API              (timeout a los 10 s)
UNKNOWN  → APPROVED  RECONCILIATION   (~45 s después)
```

## 5. Probar la conciliación diaria (liquidación)

```bash
./scripts/demo-liquidacion.sh
```

Crea 4 pagos reales y escribe el archivo de liquidación del día en `local/liquidacion/DEMO_<fecha UTC>.csv` con diferencias puestas a propósito. Luego ejecuta la conciliación (`POST /admin/settlement/run?date=…`): carga en lote, cruce por conjuntos con `pkg_settlement.compare` en PL/SQL y corrección automática.

| Caso | Aquí | En la liquidación | Resultado |
| --- | --- | --- | --- |
| A | APPROVED | APPROVED 150.000 | Sin discrepancia |
| B | APPROVED | No aparece | `NOT_SETTLED`, revisión manual |
| C | PENDING (webhook perdido) | APPROVED | `APPROVED_NOT_HERE` → **AUTO_FIXED**: el pago pasa a APPROVED con origen `SETTLEMENT` |
| D | APPROVED 150.000 | APPROVED 140.000 | `AMOUNT_MISMATCH`, revisión manual |
| ? | No existe | APPROVED | `APPROVED_NOT_HERE`, revisión manual |

Al final repite la corrida y comprueba que no se duplica nada (idempotencia). En producción el job corre a las 04:30 UTC sobre el día anterior, con ShedLock para que lo ejecute un solo nodo. Para ver las discrepancias de un día: `curl "localhost:8080/admin/settlement/discrepancies?date=YYYY-MM-DD"`.

## 6. Apagar todo

```bash
# Ctrl+C en la terminal del servicio
docker compose down
colima stop
```
