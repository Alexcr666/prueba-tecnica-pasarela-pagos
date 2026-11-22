# Probar en línea (GitHub Codespaces)

Todo corre en el navegador: Oracle Free, RabbitMQ, una pasarela de pagos simulada y el servicio. No hay que instalar nada.

## 1. Levantar el entorno

En la terminal de abajo:

```bash
./scripts/iniciar.sh
```

Tarda 1 o 2 minutos: espera a Oracle y arranca el servicio. Termina con **"Todo arriba"**. Se puede repetir sin problema.

## 2. Ejecutar las demos

Cada script imprime lo que hace y marca con ✔ lo que verifica.

| Comando | Qué demuestra | Tiempo |
| --- | --- | --- |
| `./scripts/demo.sh` | Flujo completo: idempotencia de la API, doble clic bloqueado (409), firma inválida (401), webhook duplicado sin efecto, evento fuera de orden ignorado | 10 s |
| `./scripts/demo-timeout.sh` | La pasarela tarda más que el timeout: el pago queda `UNKNOWN`, no se reintenta el cobro y la conciliación lo resuelve sola | 1 min |
| `./scripts/demo-liquidacion.sh` | Conciliación diaria contra el archivo del proveedor: los 3 tipos de discrepancia, corrección automática e idempotencia | 15 s |

## 3. Mirar por dentro

Abre la pestaña **PORTS** (junto a la terminal) y pulsa el ícono del globo en:

- **8090 · Visor de eventos:** los eventos publicados en RabbitMQ, con el JSON en árbol desplegable.
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
