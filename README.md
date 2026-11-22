# Prueba técnica · Integración asíncrona con una pasarela de pagos

Diseño e implementación de referencia de un servicio que procesa pagos de forma asíncrona y confiable con una pasarela externa: redirección del cliente, webhooks tardíos, duplicados o fuera de orden, timeouts con resultado incierto y conciliación.

**Stack:** Java 21 · Spring Boot 4.1 · Oracle (19c+) · RabbitMQ 4.3 · Resilience4j · Flyway · Testcontainers.

## Entregables

| Entregable | Archivo |
| --- | --- |
| Documento de diseño técnico (28 páginas) | [PDF](docs/entrega/Diseno_Tecnico_Pasarela_Pagos.pdf) · [Word](docs/entrega/Diseno_Tecnico_Pasarela_Pagos.docx) · [Markdown](docs/diseno-tecnico.md) |
| Arquitectura y responsabilidades | Sección 4 |
| Diagramas de secuencia: flujo exitoso, timeout y webhooks duplicados | Sección 7 |
| Modelo de datos y DDL completo de Oracle | Sección 6 y Anexo A |
| Resiliencia: reintentos, timeouts, mensajes fallidos y conciliación | Sección 9 |
| Supuestos, reglas de negocio no especificadas y herramientas | Secciones 2 y 14 |
| Estrategia de pruebas | Sección 12 |

## Probar en línea, sin instalar nada

[![Abrir en GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/Alexcr666/prueba-tecnica-pasarela-pagos?quickstart=1)

1. Pulsa el botón (requiere una cuenta de GitHub).
2. Al abrirse, la terminal ejecuta sola `./scripts/iniciar.sh`: levanta Oracle, RabbitMQ, la pasarela simulada, el servicio y la consola. La primera vez tarda unos 5 a 8 minutos (descarga Oracle); las siguientes, 1 o 2.
3. Al terminar, la terminal muestra el enlace de la **consola de la prueba**. También está en la pestaña **PORTS** → puerto **8090** → 🌐.

Guía paso a paso: [docs/PROBAR-ONLINE.md](docs/PROBAR-ONLINE.md).

### La consola de la prueba

Ejecuta contra el servicio real los escenarios del enunciado. Cada paso explica qué hace y por qué, y muestra la petición, la respuesta, la máquina de estados y el historial guardado en Oracle.

| Pestaña | Qué muestra |
| --- | --- |
| Resumen | Indicadores, escenarios, arquitectura y enlaces a los entregables. Botón para ejecutar toda la verificación. |
| Documento de diseño | El documento completo con sus diagramas, navegable por secciones, con descarga en PDF y Word. |
| 01 · Pago y duplicados | Idempotencia de la API, doble clic bloqueado (409), firma falsa rechazada (401), webhook duplicado sin efecto, evento tardío ignorado y eventos publicados. |
| 02 · Timeout | La pasarela no responde a tiempo: el pago queda `UNKNOWN`, no se reintenta el cobro y la conciliación lo resuelve sola. |
| 03 · Conciliación diaria | Archivo de liquidación con diferencias: corrección automática, los tres tipos de discrepancia e idempotencia. |
| Métricas | Métricas del servicio en vivo (Prometheus): transiciones por origen, duplicados, firmas inválidas, circuit breaker y peticiones HTTP. |
| Eventos | Los eventos publicados en RabbitMQ, con el JSON de cada uno. |
| Requisitos de la prueba | Cada punto del enunciado con su solución, la sección del documento y el paso que lo demuestra. |

## Qué está implementado

| Pieza | Dónde (`src/main/java/com/pagos/payments`) |
| --- | --- |
| Máquina de estados del pago (tabla 5.1 del diseño) | `domain/PaymentStateMachine` |
| Transición idempotente: bloqueo de la orden, `UPDATE` condicional, historial y outbox en una transacción | `application/PaymentTransitionService` |
| Inicio de pago con dos transacciones cortas; `UNKNOWN` ante timeout, sin reintento a ciegas | `application/PaymentInitiationService` |
| Webhooks: firma HMAC-SHA256, anti-replay, lista blanca de campos e inbox con `UNIQUE` | `adapter/web/WebhookController` |
| Procesador del inbox con `SKIP LOCKED`, lease y reintentos | `application/InboxProcessor` |
| Conciliación incremental por referencia (cada 60 s, un solo nodo con ShedLock) | `application/ReconciliationService` |
| Conciliación diaria: lectura del archivo, cruce por conjuntos en PL/SQL (`pkg_settlement.compare`) y corrección segura | `adapter/settlement`, `application/SettlementReconciliationService`, `V2__settlement.sql` |
| Outbox con publisher confirms y mensajes `mandatory`: un evento sin cola de destino no se pierde | `application/OutboxRelay` |
| Eventos con sobre versionado (`eventId`, `schemaVersion`, `correlationId`) | `application/EventPublisher` |
| Adaptador de la pasarela: timeouts, reintento solo en fallo de conexión, circuit breaker y bulkhead | `adapter/provider/HttpPaymentProviderAdapter` |
| Esquema Oracle: índices sobre expresiones, intento activo único, inbox e idempotencia | `resources/db/migration/V1__schema.sql` |

Fuera del alcance de esta implementación, tal como lo explica el documento: Spring Security/JWT, integración con Vault, el proceso SFTP que entrega el archivo de liquidación y los consumidores de RabbitMQ.

### API

| Método y ruta | Uso |
| --- | --- |
| `POST /orders` (con `Idempotency-Key`) | Crea una orden. |
| `GET /orders/{id}` · `POST /orders/{id}/cancel` | Consulta o cancela una orden. |
| `POST /orders/{id}/payments` (con `Idempotency-Key`) | Inicia el pago: 201 con `redirectUrl`, 202 si el resultado es incierto, 409 si ya hay un pago activo. |
| `GET /payments/{id}` · `GET /payments/{id}/history` | Estado del pago y su ciclo de vida completo. |
| `POST /webhooks/{provider}` | Notificaciones de la pasarela, firmadas con `X-Provider-Timestamp` y `X-Provider-Signature = hex(HMAC-SHA256(secreto, timestamp + "." + cuerpo))`. |
| `POST /admin/settlement/run?date=` · `GET /admin/settlement/discrepancies?date=` | Ejecuta la conciliación diaria de un día y lista sus discrepancias (uso interno de operaciones). |
| `GET /actuator/health` · `GET /actuator/prometheus` | Salud y métricas. |

## Pruebas automatizadas

84 pruebas, en verde con `./mvnw verify`:

- **Unitarias (73):** los 49 pares origen → destino de la máquina de estados; firma válida, alterada, vencida, en rotación y con timestamp absurdo; clave de deduplicación del inbox; política de resiliencia hacia la pasarela (timeout de lectura sin reintento, fallo de conexión, 4xx, 409, 5xx y reintento de consultas).
- **Integración contra Oracle real (11):**
    - `PaymentFlowIT`: flujo exitoso; mismo webhook dos veces en paralelo con un solo efecto; eventos concurrentes; evento tardío ignorado; monto distinto a revisión; timeout → `UNKNOWN` → segundo intento bloqueado → conciliación → `APPROVED`; misma `Idempotency-Key` con una sola llamada a la pasarela.
    - `SettlementIT`: los tres tipos de discrepancia de la conciliación diaria, la corrección automática y la idempotencia de la corrida.

## Ejecutar en local

Requisitos: JDK 21 o superior y Docker. No hace falta instalar Maven (se usa `./mvnw`).

```bash
./mvnw verify                                   # 84 pruebas (las de integración levantan Oracle con Testcontainers)

docker compose up -d                            # Oracle Free, RabbitMQ y la pasarela simulada (WireMock)
WEBHOOK_SECRETS=dev-secret ./mvnw spring-boot:run   # el servicio en :8080
API_URL=http://localhost:8080 python3 scripts/consola.py   # la consola en :8090
```

O todo de una vez: `./scripts/iniciar.sh`. Los mismos escenarios también se pueden recorrer desde la terminal con `./scripts/demo.sh`, `./scripts/demo-timeout.sh` y `./scripts/demo-liquidacion.sh`. Más detalle en [docs/COMO-PROBAR.md](docs/COMO-PROBAR.md).

## Estructura

```
docs/
  diseno-tecnico.md           documento de diseño (fuente)
  entrega/                    documento en PDF, Word y HTML, con sus diagramas
  PROBAR-ONLINE.md            guía para Codespaces
  COMO-PROBAR.md              guía para ejecución local
src/main/java/.../payments/
  domain/                     máquina de estados y modelo, sin dependencias de infraestructura
  application/                casos de uso, inbox, outbox y conciliaciones
  adapter/                    web (API y webhooks), pasarela y archivo de liquidación
  persistence/                acceso a Oracle con SQL explícito
src/main/resources/db/migration/   esquema y paquete PL/SQL (Flyway)
scripts/                      arranque, consola de la prueba y demos
local/                        configuración de la pasarela simulada
```
