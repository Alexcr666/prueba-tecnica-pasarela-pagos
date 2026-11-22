#!/usr/bin/env python3
"""Consola de la prueba técnica: recorrido guiado por los escenarios del diseño.

Uso:  python3 scripts/consola.py     y abre http://localhost:8090
Solo librería estándar. La página (scripts/consola/index.html) llama a esta API local, que:
  - reenvía peticiones al servicio de pagos (solo a API_URL, nunca a otro host);
  - firma webhooks con HMAC-SHA256 como lo haría la pasarela;
  - controla la pasarela simulada (WireMock) para provocar un timeout;
  - escribe el archivo de liquidación diaria;
  - lee los eventos publicados en RabbitMQ sin consumirlos.

Variables: API_URL (http://localhost:8080), PASARELA_URL (http://localhost:8089),
RABBIT_API (http://localhost:15672/api), WEBHOOK_SECRETS (dev-secret), PORT (8090).
"""
import base64
import hashlib
import hmac
import json
import os
import pathlib
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = ROOT / "scripts" / "consola" / "index.html"
API_URL = os.environ.get("API_URL", os.environ.get("API", "http://localhost:8080")).rstrip("/")
PASARELA_URL = os.environ.get("PASARELA_URL", "http://localhost:8089").rstrip("/")
RABBIT_API = os.environ.get("RABBIT_API", "http://localhost:15672/api").rstrip("/")
RABBIT_AUTH = "Basic " + base64.b64encode(
    f"{os.environ.get('RABBIT_USER', 'guest')}:{os.environ.get('RABBIT_PASSWORD', 'guest')}".encode()).decode()
SECRET = os.environ.get("WEBHOOK_SECRETS", "dev-secret").split(",")[0].strip()
PROVIDER = os.environ.get("PROVIDER", "DEMO")
SETTLEMENT_DIR = pathlib.Path(os.environ.get("SETTLEMENT_DIR", ROOT / "local" / "liquidacion"))
QUEUE = "demo.consumidor"
EXCHANGE = "payments.events"
PORT = int(os.environ.get("PORT", "8090"))


def http(method, url, body=None, headers=None, timeout=30):
    """Petición HTTP que nunca lanza por códigos 4xx/5xx: devuelve (status, cuerpo, ms)."""
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    ms = int((time.monotonic() - start) * 1000)
    text = raw.decode("utf-8", "replace")
    try:
        parsed = json.loads(text) if text else None
    except ValueError:
        parsed = text
    return status, parsed, ms


def rabbit(method, path, body=None):
    return http(method, RABBIT_API + path, body,
                {"Authorization": RABBIT_AUTH, "content-type": "application/json"}, timeout=5)


def ensure_queue():
    """Cola de demostración enlazada a todos los eventos (en producción cada consumidor crea la suya)."""
    rabbit("PUT", f"/queues/%2F/{QUEUE}", {"durable": True, "arguments": {"x-queue-type": "quorum"}})
    rabbit("POST", f"/bindings/%2F/e/{EXCHANGE}/q/{QUEUE}", {"routing_key": "#"})


# --- acciones ------------------------------------------------------------------

def call_service(req):
    """Reenvía una petición al servicio de pagos. Solo rutas relativas: no es un proxy abierto."""
    path = req.get("path", "")
    if not path.startswith("/") or path.startswith("//"):
        return 400, {"error": "ruta inválida"}
    headers = {"content-type": "application/json"}
    headers.update({k: v for k, v in (req.get("headers") or {}).items() if k.lower() == "idempotency-key"})
    status, body, ms = http(req.get("method", "GET"), API_URL + path, req.get("body"), headers, timeout=40)
    return 200, {"status": status, "body": body, "ms": ms}


def send_webhook(req):
    """Arma el webhook como lo haría la pasarela y lo firma: HMAC-SHA256 de "timestamp.cuerpo"."""
    payload = {"id": req["eventId"], "type": "payment.updated",
               "data": {"id": req.get("providerPaymentId"), "reference": req["reference"],
                        "status": req["status"], "amount": req.get("amount", 150000), "currency": "COP"}}
    body = json.dumps(payload, separators=(",", ":"))
    ts = str(int(time.time()))
    signature = hmac.new(SECRET.encode(), f"{ts}.{body}".encode(), hashlib.sha256).hexdigest()
    if req.get("badSignature"):
        signature = "00" + signature[2:]
    headers = {"content-type": "application/json", "X-Provider-Timestamp": ts, "X-Provider-Signature": signature}
    status, resp, ms = http("POST", f"{API_URL}/webhooks/{PROVIDER}", body.encode(), headers)
    return 200, {"request": {"headers": {"X-Provider-Timestamp": ts, "X-Provider-Signature": signature[:16] + "…"},
                             "body": payload},
                 "status": status, "body": resp, "ms": ms}


def gateway_slow(_req):
    mapping = {"priority": 1, "request": {"method": "POST", "url": "/v1/payments"},
               "response": {"status": 201, "fixedDelayMilliseconds": 15000,
                            "headers": {"Content-Type": "application/json"},
                            "body": "{\"id\":\"pay-lento\",\"status\":\"PENDING\"}"}}
    status, _, _ = http("POST", PASARELA_URL + "/__admin/mappings", mapping, {"content-type": "application/json"})
    return 200, {"status": status, "delayMs": 15000}


def gateway_reset(_req):
    status, _, _ = http("POST", PASARELA_URL + "/__admin/mappings/reset")
    return 200, {"status": status}


def write_settlement(req):
    """Escribe <PROVEEDOR>_<fecha>.csv en el buzón que lee el servicio (en producción lo deja el SFTP)."""
    date = req["date"]
    if len(date) != 10 or not date.replace("-", "").isdigit():
        return 400, {"error": "fecha inválida"}
    SETTLEMENT_DIR.mkdir(parents=True, exist_ok=True)
    rows = ["provider_payment_id,merchant_reference,status,amount_minor,currency"]
    rows += [f"{l['providerPaymentId']},{l.get('reference') or ''},{l['status']},{int(l['amount'])},{l['currency']}"
             for l in req["lines"]]
    path = SETTLEMENT_DIR / f"{PROVIDER}_{date}.csv"
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return 200, {"file": str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),
                 "content": "\n".join(rows)}


def events(_req=None):
    ensure_queue()
    status, msgs, _ = rabbit("POST", f"/queues/%2F/{QUEUE}/get",
                             {"count": 500, "ackmode": "ack_requeue_true", "encoding": "auto"})
    if status != 200:
        return 502, {"error": f"RabbitMQ respondió {status}"}
    out = []
    for m in msgs or []:
        try:
            payload = json.loads(m["payload"])
        except (ValueError, TypeError):
            payload = m["payload"]
        out.append({"routingKey": m.get("routing_key"), "exchange": m.get("exchange"),
                    "properties": m.get("properties", {}), "payload": payload})
    return 200, {"queue": QUEUE, "messages": out}


def purge(_req):
    rabbit("DELETE", f"/queues/%2F/{QUEUE}/contents")
    return 200, {"ok": True}


def health(_req=None):
    def up(fn):
        try:
            return fn()
        except (urllib.error.URLError, OSError, ValueError):
            return False
    service = up(lambda: http("GET", API_URL + "/actuator/health", timeout=3)[1].get("status") == "UP")
    broker = up(lambda: rabbit("GET", "/overview")[0] == 200)
    gateway = up(lambda: http("GET", PASARELA_URL + "/__admin/health", timeout=3)[0] == 200)
    return 200, {"service": service, "broker": broker, "gateway": gateway, "apiUrl": API_URL}


ROUTES = {
    ("GET", "/api/health"): health,
    ("GET", "/api/events"): events,
    ("POST", "/api/service"): call_service,
    ("POST", "/api/webhook"): send_webhook,
    ("POST", "/api/gateway/slow"): gateway_slow,
    ("POST", "/api/gateway/reset"): gateway_reset,
    ("POST", "/api/settlement-file"): write_settlement,
    ("POST", "/api/events/purge"): purge,
}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("content-type", ctype + "; charset=utf-8")
        self.send_header("cache-control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _route(self, method):
        path = self.path.split("?")[0]
        if method == "GET" and path in ("/", "/index.html"):
            return self._send(200, PAGE.read_bytes(), "text/html")
        handler = ROUTES.get((method, path))
        if handler is None:
            return self._send(404, {"error": "no encontrado"})
        try:
            length = int(self.headers.get("content-length") or 0)
            req = json.loads(self.rfile.read(length)) if length else {}
            code, body = handler(req)
            self._send(code, body)
        except (urllib.error.URLError, OSError) as e:
            self._send(502, {"error": f"No hay conexión: {e}"})
        except (KeyError, ValueError) as e:
            self._send(400, {"error": f"Petición inválida: {e}"})

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    try:
        ensure_queue()
    except (urllib.error.URLError, OSError):
        print("Aviso: RabbitMQ aún no responde; la cola de demo se creará al consultar eventos.")
    print(f"Consola en http://localhost:{PORT}  (servicio {API_URL}, Ctrl+C para salir)")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
