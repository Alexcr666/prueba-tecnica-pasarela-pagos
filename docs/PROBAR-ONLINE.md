# Probar en línea

## Opción 1: enlace directo

https://prueba-tecnica-pasarela-pagos-launcher-gelsa.vercel.app

Abre la consola de la prueba. El sistema corre en un GitHub Codespace que se apaga cuando nadie lo usa; la página revisa si está encendido, lo enciende si hace falta y redirige a la consola cuando Oracle, RabbitMQ y el servicio responden. Si estaba apagado tarda entre 1 y 3 minutos.

## Opción 2: entorno propio en GitHub Codespaces

Desde el README, botón "Open in GitHub Codespaces" (requiere cuenta de GitHub).

1. La primera vez tarda unos 8 minutos: compila el servicio y descarga Oracle, RabbitMQ y la pasarela simulada.
2. Al abrirse, la terminal ejecuta `./scripts/iniciar.sh`, que levanta todo en contenedores y al final muestra el enlace de la consola. También está en la pestaña PORTS, puerto 8090.
3. Los contenedores se reinician solos: si el Codespace se apaga y se vuelve a encender, todo queda arriba sin ejecutar nada.

## Qué probar en la consola

| Sección | Qué demuestra | Tiempo |
| --- | --- | --- |
| 01 Pago y duplicados | Idempotencia de la API, doble clic bloqueado (409), firma falsa rechazada (401), webhook repetido sin efecto, evento tardío ignorado y eventos publicados | 20 s |
| 02 Timeout | La pasarela tarda más que el límite: el pago queda `UNKNOWN`, no se reintenta el cobro y la conciliación lo resuelve | 1-2 min |
| 03 Conciliación diaria | Archivo de liquidación con los tres tipos de discrepancia, corrección automática e idempotencia | 20 s |

En Resumen, "Ejecutar la verificación completa" recorre los tres seguidos. También se puede ejecutar cada paso con su botón; cada uno explica qué hace y muestra la petición, la respuesta y el historial en Oracle.

Las secciones Métricas, Eventos, Requisitos de la prueba y Documento de diseño muestran, respectivamente, las métricas del servicio, los mensajes publicados en RabbitMQ, cada punto del enunciado con su evidencia y el documento completo.

## Desde la terminal del Codespace

```bash
./scripts/demo.sh               # flujo completo, duplicados y fuera de orden
./scripts/demo-timeout.sh       # timeout, UNKNOWN y conciliación
./scripts/demo-liquidacion.sh   # conciliación diaria
./mvnw verify                   # 84 pruebas automatizadas
```

Antes de `./mvnw verify` conviene detener el Oracle del entorno (`docker compose stop oracle`) para liberar memoria, y volver a levantarlo después con `docker compose start oracle`.

Historial de un pago en Oracle:

```bash
docker compose exec oracle sqlplus payments/payments@FREEPDB1
SELECT from_status, to_status, source, occurred_at FROM payment_status_history ORDER BY id;
```
