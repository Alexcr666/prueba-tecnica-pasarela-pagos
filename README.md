# Payment Service — implementación de referencia

Integración asíncrona con una pasarela de pagos. El diseño completo está en
[docs/diseno-tecnico.md](docs/diseno-tecnico.md) (también en [PDF](docs/entrega/Diseno_Tecnico_Pasarela_Pagos.pdf)). Este repositorio implementa su núcleo.

## Probar en línea, sin instalar nada

[![Abrir en GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/Alexcr666/prueba-tecnica-pasarela-pagos?quickstart=1)

1. Pulsa el botón (requiere una cuenta de GitHub).
2. Al abrirse, la terminal ejecuta sola `./scripts/iniciar.sh`: levanta Oracle, RabbitMQ, la pasarela simulada, el servicio y la consola. La primera vez tarda unos 5 a 8 minutos (descarga Oracle); las siguientes, 1 o 2.
3. Al terminar, la terminal muestra el enlace de la **consola de la prueba** (también en la pestaña **PORTS** → puerto **8090** → 🌐). Recorre con botones, paso a paso, los escenarios de éxito, duplicados, timeout y conciliación.

Guía paso a paso: [docs/PROBAR-ONLINE.md](docs/PROBAR-ONLINE.md).

## Qué está implementado

| Pieza | Dónde |
| --- | --- |
| Máquina de estados (tabla 5.1) | `domain/PaymentStateMachine` |
| Transición idempotente: bloqueo de la orden + UPDATE condicional + historial + outbox | `application/PaymentTransitionService` |
| Inicio de pago con dos transacciones cortas y `UNKNOWN` ante timeout | `application/PaymentInitiationService` |
| Receptor de webhooks: HMAC, anti-replay, lista blanca de campos, inbox | `adapter/web/WebhookController` |
| Procesador del inbox con `SKIP LOCKED` + lease + reintentos | `application/InboxProcessor` |
| Conciliación incremental por referencia | `application/ReconciliationService` |
| Conciliación diaria contra la liquidación: lector del archivo CSV, staging + `pkg_settlement.compare` por conjuntos + corrección con `transition(..., SETTLEMENT)` | `adapter/settlement/CsvSettlementFileAdapter`, `application/SettlementReconciliationService`, `resources/db/migration/V2__settlement.sql` |
| Relay del outbox con publisher confirms y mensajes `mandatory` (un evento sin cola de destino no se pierde) | `application/OutboxRelay` |
| Adaptador del proveedor: timeouts, retry solo en fallo de conexión, circuit breaker, bulkhead | `adapter/provider/HttpPaymentProviderAdapter` |
| Esquema Oracle (índices sobre expresiones, UNIQUE del inbox, idempotencia) | `resources/db/migration/V1__schema.sql` |

No incluido, por estar fuera del foco: Spring Security/JWT, integración con Vault, el proceso SFTP que deja el archivo de liquidación y consumidores de RabbitMQ.

## Requisitos

- JDK 21 o superior (producción: JDK 25 LTS).
- Docker, para las pruebas de integración y el entorno local.
- No hace falta instalar Maven: usa `./mvnw`.

## Docker en esta Mac (Colima)

```bash
colima start --cpu 4 --memory 4 --vm-type vz          # arranca Docker
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export WEBHOOK_SECRETS=dev-secret                      # obligatorio para levantar la app
```

## Comandos

```bash
./mvnw test             # pruebas unitarias (no necesitan Docker)
./mvnw verify           # + pruebas de integración contra Oracle real (Testcontainers)

docker compose up -d    # Oracle Free + RabbitMQ en local
./mvnw spring-boot:run  # levanta el servicio en :8080
```

## Probar a mano

```bash
# 1. Crear orden (idempotente)
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: k-1' -d '{"customerId":"c-1","amountMinor":150000,"currency":"COP"}'

# 2. Iniciar pago (necesita un proveedor en PROVIDER_BASE_URL; en pruebas lo simula WireMock)
curl -s -X POST localhost:8080/orders/<orderId>/payments -H 'Idempotency-Key: p-1'

# 3. Consultar estado
curl -s localhost:8080/payments/<attemptId>
```

Webhook firmado: `X-Provider-Timestamp` (epoch en segundos) y
`X-Provider-Signature = hex(HMAC-SHA256(secret, timestamp + "." + body))`.

## Escenarios cubiertos por las pruebas

- **Unitarias (69):**
    - los 49 pares origen → destino de la máquina de estados;
    - firma válida, alterada, vencida y en rotación;
    - política de resiliencia del proveedor: timeout de lectura sin reintento, fallo de conexión, 4xx, 409, 5xx y reintento de consultas.
- **Integración (`PaymentFlowIT`, Oracle real):**
    - flujo exitoso;
    - mismo webhook ×2 concurrente → un solo efecto;
    - eventos distintos concurrentes → `APPROVED`;
    - evento tardío → `IGNORED_STALE`;
    - monto distinto → `REQUIRES_REVIEW`;
    - timeout → `UNKNOWN` → segundo intento bloqueado (409) → conciliación → `APPROVED`;
    - misma `Idempotency-Key` → el proveedor se llama una sola vez.
