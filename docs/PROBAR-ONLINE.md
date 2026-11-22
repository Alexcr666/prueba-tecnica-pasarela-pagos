# Probar en línea (GitHub Codespaces)

Todo corre en el navegador: Oracle Free, RabbitMQ, una pasarela de pagos simulada y el servicio. No hay que instalar nada.

## 1. Esperar a que todo arranque

Al abrir el Codespace, el entorno se levanta solo (Oracle, RabbitMQ, la pasarela simulada, el servicio y la consola). Tarda 2 o 3 minutos. Si quieres verlo o relanzarlo, en la terminal:

```bash
./scripts/iniciar.sh
```

Termina con **"Todo arriba"**. Se puede repetir sin problema.

## 2. Abrir la consola de la prueba

Pestaña **PORTS** (junto a la terminal) → puerto **8090 · Consola de la prueba** → ícono del globo 🌐.

La consola recorre con botones los tres escenarios del enunciado. Cada paso explica qué hace y por qué importa, y muestra la petición, la respuesta y el historial guardado en Oracle:

| Escenario | Qué demuestra |
| --- | --- |
| 1 · Pago y duplicados | Idempotencia de la API, doble clic bloqueado (409), firma falsa rechazada (401), webhook duplicado sin efecto, evento fuera de orden ignorado y eventos publicados en RabbitMQ |
| 2 · Timeout | La pasarela no responde a tiempo: el pago queda `UNKNOWN`, no se reintenta el cobro y la conciliación lo resuelve sola |
| 3 · Conciliación diaria | Archivo de liquidación con diferencias: corrección automática, los 3 tipos de discrepancia e idempotencia |

Pulsa **▶ Ejecutar toda la prueba** en el inicio, o ejecuta cada paso con su botón. La pestaña **Eventos publicados** muestra lo que llegó a RabbitMQ.

También se puede desde la terminal: `./scripts/demo.sh`, `./scripts/demo-timeout.sh` y `./scripts/demo-liquidacion.sh`.

## 3. Mirar por dentro

Abre la pestaña **PORTS** (junto a la terminal) y pulsa el ícono del globo en:

- **15672 · RabbitMQ:** consola del broker (usuario `guest`, clave `guest`).
- **8080 · API del servicio:** `/actuator/health`, `/actuator/prometheus`.

Historial de un pago en Oracle:

```bash
docker compose exec oracle sqlplus payments/payments@FREEPDB1
SELECT from_status, to_status, source, occurred_at FROM payment_status_history ORDER BY id;
```

## 4. Pruebas automatizadas

```bash
docker compose stop oracle      # libera memoria para el Oracle de las pruebas
./mvnw verify                   # 84 pruebas: 73 unitarias + 11 de integración contra Oracle real
docker compose start oracle
```

Debe terminar en `BUILD SUCCESS`. Luego vuelve a correr `./scripts/iniciar.sh`.

## Documentos

- Diseño técnico: [diseno-tecnico.md](diseno-tecnico.md) · [PDF](entrega/Diseno_Tecnico_Pasarela_Pagos.pdf)
- Código: `src/main/java/com/pagos/payments` (dominio, aplicación, adaptadores, persistencia)
