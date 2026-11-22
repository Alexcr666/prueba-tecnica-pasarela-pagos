# Integración asíncrona con una pasarela de pagos

Prueba técnica para el cargo de Desarrollador Java Senior. Incluye el documento de diseño que pide el enunciado y una implementación de referencia que se puede ejecutar: un servicio de pagos que integra una pasarela externa tolerando webhooks tardíos, duplicados o fuera de orden, timeouts con resultado incierto y conciliación.

Stack: Java 21, Spring Boot 4.1, Oracle (19c en adelante), RabbitMQ 4.3, Resilience4j, Flyway y Testcontainers.

## Dónde revisar

| Qué | Dónde |
| --- | --- |
| Documento de diseño (entregable) | [PDF](docs/entrega/Diseno_Tecnico_Pasarela_Pagos.pdf) · [Word](docs/entrega/Diseno_Tecnico_Pasarela_Pagos.docx) · [Markdown](docs/diseno-tecnico.md) |
| Demo en línea, sin instalar nada | https://prueba-tecnica-pasarela-pagos-launcher-gelsa.vercel.app |
| Código fuente | Este repositorio (`src/main/java/com/pagos/payments`) |

**Sobre la demo en línea.** El sistema completo (servicio, Oracle, RabbitMQ y una pasarela simulada) corre en un GitHub Codespace que se apaga solo cuando nadie lo usa. El enlace de arriba abre una página que revisa si está encendido; si no, lo enciende, espera a que la base de datos quede lista y abre la consola. Si estaba apagado tarda entre 1 y 3 minutos; si ya estaba encendido, unos segundos. Antes de la consola, GitHub muestra una vez un aviso de seguridad ("You are about to access a development port"); hay que pulsar Continue.

Si el enlace no responde, la alternativa es crear un entorno propio con el botón de abajo. Necesita una cuenta de GitHub y tarda unos 8 minutos la primera vez, porque descarga Oracle.

[![Abrir en GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/Alexcr666/prueba-tecnica-pasarela-pagos?quickstart=1)

## Qué es cada cosa

| Pieza | Qué hace |
| --- | --- |
| Servicio de pagos (`src/`) | La implementación. Expone la API de órdenes y pagos, recibe los webhooks y corre la conciliación. |
| Oracle | Fuente de verdad: órdenes, intentos de pago, historial, inbox, outbox y discrepancias. |
| RabbitMQ | Recibe los eventos que publica el servicio (`PaymentStatusChanged`, `OrderPaid`, etc.). |
| Pasarela simulada (`local/wiremock`) | Hace de proveedor de pagos: crea pagos en `PENDING`, responde consultas y se puede volver lenta para provocar un timeout. |
| Consola de la prueba (`scripts/consola`) | Página web que ejecuta los escenarios del enunciado contra el servicio real y muestra cada petición, la respuesta, la máquina de estados y el historial en Oracle. |
| Página de inicio (`launcher/`) | La página publicada en Vercel que enciende el entorno y redirige a la consola. |
| Scripts (`scripts/`) | `iniciar.sh` levanta todo; `demo.sh`, `demo-timeout.sh` y `demo-liquidacion.sh` recorren los escenarios desde la terminal. |

## La consola

| Sección | Contenido |
| --- | --- |
| Resumen | Estado de la verificación, arquitectura y acceso a los entregables. El botón "Ejecutar la verificación completa" recorre los tres escenarios (unos 3 minutos). |
| Documento de diseño | El documento completo con sus diagramas, navegable por secciones, con descarga en PDF y Word. |
| 01 Pago y duplicados | Idempotencia de la API, doble clic bloqueado (409), firma falsa rechazada (401), webhook repetido sin efecto, evento tardío ignorado y eventos publicados en RabbitMQ. |
| 02 Timeout | La pasarela tarda más que el límite: el pago queda `UNKNOWN`, no se reintenta el cobro y la conciliación lo resuelve. |
| 03 Conciliación diaria | Archivo de liquidación con diferencias: corrección automática de lo seguro y registro de lo que requiere revisión. |
| Métricas | Métricas del servicio en vivo (Prometheus). |
| Eventos | Los mensajes publicados en RabbitMQ, con su contenido. |
| Requisitos de la prueba | Cada punto del enunciado con la sección del documento que lo explica y el paso de la consola que lo demuestra. |

## Implementación

| Pieza | Clase o archivo |
| --- | --- |
| Máquina de estados del pago | `domain/PaymentStateMachine` |
| Transición idempotente: bloqueo de la orden, `UPDATE` condicional, historial y outbox en una transacción | `application/PaymentTransitionService` |
| Inicio de pago en dos transacciones cortas; `UNKNOWN` ante timeout, sin reintento a ciegas | `application/PaymentInitiationService` |
| Webhooks: firma HMAC-SHA256, anti-replay, lista blanca de campos e inbox con `UNIQUE` | `adapter/web/WebhookController` |
| Procesador del inbox con `SKIP LOCKED`, lease y reintentos | `application/InboxProcessor` |
| Conciliación incremental cada 60 s (un solo nodo con ShedLock) | `application/ReconciliationService` |
| Conciliación diaria: lectura del archivo, cruce por conjuntos en PL/SQL y corrección segura | `adapter/settlement`, `application/SettlementReconciliationService`, `V2__settlement.sql` |
| Outbox con confirmación del broker; un evento sin cola de destino no se pierde | `application/OutboxRelay` |
| Eventos con sobre versionado (`eventId`, `schemaVersion`, `correlationId`) | `application/EventPublisher` |
| Cliente de la pasarela: timeouts, reintento solo en fallo de conexión, circuit breaker y bulkhead | `adapter/provider/HttpPaymentProviderAdapter` |
| Esquema Oracle | `resources/db/migration/V1__schema.sql` |

Quedan fuera de esta implementación, como se explica en el documento: Spring Security/JWT, la integración con Vault, el proceso SFTP que entrega el archivo de liquidación y los consumidores de RabbitMQ.

## API

| Método y ruta | Uso |
| --- | --- |
| `POST /orders` (con `Idempotency-Key`) | Crea una orden. |
| `GET /orders/{id}`, `POST /orders/{id}/cancel` | Consulta o cancela una orden. |
| `POST /orders/{id}/payments` (con `Idempotency-Key`) | Inicia el pago: 201 con `redirectUrl`, 202 si el resultado es incierto, 409 si ya hay un pago activo. |
| `GET /payments/{id}`, `GET /payments/{id}/history` | Estado del pago y su ciclo de vida. |
| `POST /webhooks/{provider}` | Notificaciones de la pasarela. Cabeceras `X-Provider-Timestamp` y `X-Provider-Signature = hex(HMAC-SHA256(secreto, timestamp + "." + cuerpo))`. |
| `POST /admin/settlement/run?date=`, `GET /admin/settlement/discrepancies?date=` | Conciliación diaria de un día y sus discrepancias (uso interno). |
| `GET /actuator/health`, `GET /actuator/prometheus` | Salud y métricas. |

## Pruebas automatizadas

84 pruebas con `./mvnw verify`:

- 73 unitarias: las 49 combinaciones de la máquina de estados, verificación de firmas (válida, alterada, vencida, en rotación), clave de deduplicación del inbox y política de resiliencia hacia la pasarela.
- 11 de integración contra Oracle real con Testcontainers. `PaymentFlowIT` cubre el flujo exitoso, webhooks duplicados en paralelo, eventos concurrentes, evento tardío, monto distinto, timeout resuelto por conciliación e idempotencia de la API. `SettlementIT` cubre la conciliación diaria.

## Ejecutar en local

Requisitos: JDK 21 o superior y Docker. Maven no hace falta (se usa `./mvnw`).

```bash
./mvnw verify            # 84 pruebas; las de integración levantan Oracle con Testcontainers
./scripts/iniciar.sh     # compila y levanta todo en contenedores; la consola queda en http://localhost:8090
```

`docker compose up -d` levanta solo la infraestructura (Oracle, RabbitMQ y la pasarela simulada), útil para ejecutar el servicio desde el IDE con `WEBHOOK_SECRETS=dev-secret ./mvnw spring-boot:run`. Más detalle en [docs/COMO-PROBAR.md](docs/COMO-PROBAR.md).

## Estructura

```
docs/                     documento de diseño (Markdown) y guías de prueba
docs/entrega/             documento en PDF, Word y HTML, con sus diagramas
src/main/java/.../        domain, application, adapter y persistence
src/main/resources/       configuración y migraciones de Oracle (Flyway)
scripts/                  arranque, consola de la prueba y demos por terminal
launcher/                 página de inicio de la demo en línea (Vercel)
local/                    configuración de la pasarela simulada
```
