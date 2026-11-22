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
import threading
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = ROOT / "scripts" / "consola" / "index.html"
DOCS = (ROOT / "docs" / "entrega").resolve()
DOC_TYPES = {".html": "text/html", ".png": "image/png", ".pdf": "application/pdf",
             ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}
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


def rabbit(method, path, body=None, timeout=15):
    return http(method, RABBIT_API + path, body,
                {"Authorization": RABBIT_AUTH, "content-type": "application/json"}, timeout=timeout)


def ensure_queue():
    """Cola de demostración enlazada a todos los eventos (en producción cada consumidor crea la suya)."""
    rabbit("PUT", f"/queues/%2F/{QUEUE}", {"durable": True, "arguments": {"x-queue-type": "quorum"}})
    rabbit("POST", f"/bindings/%2F/e/{EXCHANGE}/q/{QUEUE}", {"routing_key": "#"})


# --- acciones ------------------------------------------------------------------

JOBS = {}
JOBS_LOCK = threading.Lock()


def call_service(req):
    """Reenvía una petición al servicio de pagos. Solo rutas relativas: no es un proxy abierto.

    Con "async": true la llamada corre en segundo plano y se devuelve un id de trabajo: las
    llamadas largas (el timeout de 10 s) no dependen de que un proxy intermedio, como el de
    Codespaces, mantenga abierta la conexión del navegador.
    """
    path = req.get("path", "")
    if not path.startswith("/") or path.startswith("//"):
        return 400, {"error": "ruta inválida"}
    headers = {"content-type": "application/json"}
    headers.update({k: v for k, v in (req.get("headers") or {}).items() if k.lower() == "idempotency-key"})

    def run():
        status, body, ms = http(req.get("method", "GET"), API_URL + path, req.get("body"), headers, timeout=40)
        return {"status": status, "body": body, "ms": ms}

    if not req.get("async"):
        return 200, run()
    job = uuid.uuid4().hex
    with JOBS_LOCK:
        JOBS[job] = None

    def work():
        try:
            result = run()
        except (urllib.error.URLError, OSError) as e:
            result = {"error": f"No hay conexión: {e}"}
        with JOBS_LOCK:
            JOBS[job] = result
    threading.Thread(target=work, daemon=True).start()
    return 200, {"job": job}


def job_status(query):
    job = urllib.parse.parse_qs(query).get("id", [""])[0]
    with JOBS_LOCK:
        if job not in JOBS:
            return 404, {"error": "trabajo desconocido"}
        result = JOBS[job]
        if result is None:
            return 200, {"done": False}
        del JOBS[job]
    return 200, {"done": True, "result": result}


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


def gateway_slow(req):
    """La pasarela simulada tarda 15 s, solo para los pagos del monto indicado.

    Así el escenario de timeout no afecta a otros escenarios ni a otras personas que usen la
    consola al mismo tiempo.
    """
    amount = int(req.get("amount", 175000))
    mapping = {"priority": 1,
               "request": {"method": "POST", "url": "/v1/payments",
                           "bodyPatterns": [{"matchesJsonPath": {"expression": "$.amount", "equalTo": str(amount)}}]},
               "response": {"status": 201, "fixedDelayMilliseconds": 15000,
                            "headers": {"Content-Type": "application/json"},
                            "body": "{\"id\":\"pay-lento\",\"status\":\"PENDING\"}"}}
    status, body, _ = http("POST", PASARELA_URL + "/__admin/mappings", mapping, {"content-type": "application/json"})
    return 200, {"status": status, "delayMs": 15000, "amount": amount, "mappingId": (body or {}).get("id")}


def gateway_reset(req):
    """Quita la demora creada por gateway_slow y, si se indica, deja a la pasarela respondiendo
    APPROVED con el monto real cuando la conciliación consulte por esa referencia."""
    if req.get("mappingId"):
        http("DELETE", f"{PASARELA_URL}/__admin/mappings/{req['mappingId']}")
    if req.get("reference"):
        lookup = {"priority": 1,
                  "request": {"method": "GET", "urlPath": "/v1/payments",
                              "queryParameters": {"reference": {"equalTo": req["reference"]}}},
                  "response": {"status": 200, "headers": {"Content-Type": "application/json"},
                               "jsonBody": {"id": "pay-" + req["reference"][-10:], "reference": req["reference"],
                                            "status": "APPROVED", "amount": int(req.get("amount", 150000)),
                                            "currency": "COP"}}}
        http("POST", PASARELA_URL + "/__admin/mappings", lookup, {"content-type": "application/json"})
    return 200, {"status": 200}


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


METRIC_PREFIXES = ("payments_", "resilience4j_circuitbreaker_state", "http_server_requests_seconds_count",
                   "http_server_requests_seconds_sum", "hikaricp_connections_active", "jvm_threads_live")


def metrics(_req=None):
    """Métricas del servicio (Prometheus) relevantes para la prueba, como lista {name, labels, value}."""
    req = urllib.request.Request(API_URL + "/actuator/prometheus")
    with urllib.request.urlopen(req, timeout=5) as r:
        text = r.read().decode()
    out = []
    for line in text.splitlines():
        if not line.startswith(METRIC_PREFIXES):
            continue
        head, _, value = line.rpartition(" ")
        name, _, raw = head.partition("{")
        labels = {}
        for part in raw.rstrip("}").split('",'):
            if "=" in part:
                k, _, v = part.partition("=")
                labels[k.strip(",")] = v.strip('"')
        try:
            out.append({"name": name, "labels": labels, "value": float(value)})
        except ValueError:
            continue
    return 200, {"metrics": out}


def health(_req=None):
    def up(fn):
        try:
            return fn()
        except (urllib.error.URLError, OSError, ValueError):
            return False
    service = up(lambda: http("GET", API_URL + "/actuator/health", timeout=3)[1].get("status") == "UP")
    broker = up(lambda: rabbit("GET", "/overview", timeout=5)[0] == 200)
    gateway = up(lambda: http("GET", PASARELA_URL + "/__admin/health", timeout=3)[0] == 200)
    return 200, {"service": service, "broker": broker, "gateway": gateway, "apiUrl": API_URL}


ROUTES = {
    ("GET", "/api/health"): health,
    ("GET", "/api/events"): events,
    ("GET", "/api/metrics"): metrics,
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
        self.send_header("content-length", str(len(data)))
        self.send_header("cache-control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _route(self, method):
        path, _, query = self.path.partition("?")
        if method == "GET" and path == "/api/job":
            return self._send(*job_status(query))
        if method == "GET" and path in ("/", "/index.html"):
            return self._send(200, PAGE.read_bytes(), "text/html")
        if method == "GET" and path.startswith("/doc/"):
            return self._doc(path[len("/doc/"):])
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

    def _doc(self, rel):
        """Documento de diseño y sus imágenes. Solo archivos de docs/entrega con extensión conocida."""
        target = (DOCS / urllib.parse.unquote(rel)).resolve()
        ctype = DOC_TYPES.get(target.suffix.lower())
        if ctype is None or not target.is_relative_to(DOCS) or not target.is_file():
            return self._send(404, {"error": "no encontrado"})
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("content-type", ctype)
        self.send_header("content-length", str(len(data)))
        if target.suffix.lower() in (".pdf", ".docx"):
            self.send_header("content-disposition", f'attachment; filename="{target.name}"')
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def log_message(self, *args):
        pass


def ensure_queue_until_ready():
    """En contenedores la consola puede arrancar antes que RabbitMQ: reintenta hasta crear la cola."""
    while True:
        try:
            status, _, _ = rabbit("PUT", f"/queues/%2F/{QUEUE}", {"durable": True, "arguments": {"x-queue-type": "quorum"}})
            if status in (201, 204):
                bound, _, _ = rabbit("POST", f"/bindings/%2F/e/{EXCHANGE}/q/{QUEUE}", {"routing_key": "#"})
                if bound in (201, 204):   # el exchange lo crea el servicio al conectarse
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(3)


if __name__ == "__main__":
    threading.Thread(target=ensure_queue_until_ready, daemon=True).start()
    print(f"Consola en http://localhost:{PORT}  (servicio {API_URL}, Ctrl+C para salir)")
    ThreadingHTTPServer((os.environ.get("HOST", "127.0.0.1"), PORT), Handler).serve_forever()
