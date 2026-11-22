#!/usr/bin/env python3
"""Visor de eventos de RabbitMQ: muestra los mensajes de una cola como JSON desplegable.

Uso:   python3 scripts/visor-eventos.py        y abre http://localhost:8090
Lee los mensajes sin consumirlos (ack_requeue_true). Si la cola de demo no existe, la crea
enlazada a payments.events con '#'. Solo librería estándar.
"""
import base64
import json
import os
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RABBIT = os.environ.get("RABBIT_API", "http://localhost:15672/api")
AUTH = "Basic " + base64.b64encode(
    f"{os.environ.get('RABBIT_USER', 'guest')}:{os.environ.get('RABBIT_PASSWORD', 'guest')}".encode()).decode()
QUEUE = os.environ.get("QUEUE", "demo.consumidor")
EXCHANGE = "payments.events"
PORT = int(os.environ.get("PORT", "8090"))


def rabbit(method, path, body=None):
    req = urllib.request.Request(RABBIT + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": AUTH, "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def ensure_queue():
    rabbit("PUT", f"/queues/%2F/{QUEUE}", {"durable": True, "arguments": {"x-queue-type": "quorum"}})
    rabbit("POST", f"/bindings/%2F/e/{EXCHANGE}/q/{QUEUE}", {"routing_key": "#"})


def peek(count):
    msgs = rabbit("POST", f"/queues/%2F/{QUEUE}/get",
                  {"count": count, "ackmode": "ack_requeue_true", "encoding": "auto"}) or []
    out = []
    for m in msgs:
        try:
            payload = json.loads(m["payload"])
        except (ValueError, TypeError):
            payload = m["payload"]
        out.append({"exchange": m.get("exchange"), "routingKey": m.get("routing_key"),
                    "redelivered": m.get("redelivered"), "properties": m.get("properties", {}),
                    "payload": payload})
    return out


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("content-type", ctype + "; charset=utf-8")
        self.send_header("cache-control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            if self.path == "/" or self.path.startswith("/?"):
                return self._send(200, PAGE, "text/html")
            if self.path.startswith("/api/messages"):
                q = rabbit("GET", f"/queues/%2F/{QUEUE}")
                return self._send(200, json.dumps({"queue": QUEUE, "total": q.get("messages", 0),
                                                   "messages": peek(200)}))
            self._send(404, '{"error":"not found"}')
        except urllib.error.URLError as e:
            self._send(502, json.dumps({"error": f"RabbitMQ no responde: {e}"}))

    def do_POST(self):
        try:
            if self.path == "/api/purge":
                rabbit("DELETE", f"/queues/%2F/{QUEUE}/contents")
                return self._send(200, '{"ok":true}')
            self._send(404, '{"error":"not found"}')
        except urllib.error.URLError as e:
            self._send(502, json.dumps({"error": str(e)}))

    def log_message(self, *args):
        pass


PAGE = r"""<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Visor de eventos</title>
<style>
:root{--bg:#f6f7f9;--card:#fff;--fg:#1d2330;--muted:#6b7280;--line:#e3e6eb;--key:#7c2d92;--str:#0f7b3f;
--num:#1d4ed8;--bool:#b45309;--null:#9ca3af;--accent:#2563eb;--chip:#eef2ff}
@media (prefers-color-scheme:dark){:root{--bg:#111318;--card:#1a1d24;--fg:#e6e8ec;--muted:#9aa1ad;--line:#2b303a;
--key:#d8a4f0;--str:#7ed69c;--num:#8ab4ff;--bool:#f5b56b;--null:#6b7280;--accent:#6ea0ff;--chip:#232a3d}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
header{position:sticky;top:0;z-index:2;background:var(--card);border-bottom:1px solid var(--line);padding:12px 16px;
display:flex;flex-wrap:wrap;gap:10px;align-items:center}
h1{font-size:17px;margin:0 12px 0 0}.muted{color:var(--muted)}
button,input{font:inherit;border:1px solid var(--line);background:var(--card);color:var(--fg);border-radius:8px;padding:6px 12px;cursor:pointer}
button:hover{border-color:var(--accent)}button.primary{background:var(--accent);border-color:var(--accent);color:#fff}
input{cursor:text;min-width:220px}
main{max-width:1100px;margin:0 auto;padding:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;margin-bottom:14px;overflow:hidden}
.head{display:flex;flex-wrap:wrap;gap:8px;align-items:center;padding:10px 14px;border-bottom:1px solid var(--line);cursor:pointer}
.n{font-weight:600;color:var(--muted);min-width:28px}
.type{font-weight:700;padding:2px 10px;border-radius:999px;background:var(--chip)}
.t-PaymentStatusChanged{color:#2563eb}.t-OrderPaid{color:#059669}.t-RefundRequired{color:#dc2626}.t-PaymentNeedsReview{color:#d97706}
.trans{font-family:ui-monospace,Menlo,monospace;font-size:13px}
.right{margin-left:auto;display:flex;gap:6px}
.meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:4px 16px;padding:10px 14px;
border-bottom:1px solid var(--line);font-size:12.5px}
.meta b{color:var(--muted);font-weight:500}.meta code{font-size:12px;word-break:break-all}
.tabs{display:flex;gap:2px;padding:8px 14px 0}.tab{border-radius:8px 8px 0 0;border-bottom:none;padding:4px 14px}
.tab.on{background:var(--chip);font-weight:600}
.body{padding:10px 14px 14px;background:var(--chip);margin:0 14px 14px;border-radius:0 8px 8px 8px}
.tree,pre{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;margin:0;overflow-x:auto}
.tree ul{list-style:none;margin:0;padding-left:20px;border-left:1px dashed var(--line)}.tree>ul{padding-left:0;border:none}
.tree li{margin:1px 0}.tg{cursor:pointer;user-select:none;display:inline-block;width:16px;color:var(--muted)}
.k{color:var(--key)}.s{color:var(--str)}.num{color:var(--num)}.b{color:var(--bool)}.nl{color:var(--null)}
.cnt{color:var(--muted);font-size:12px;margin-left:6px}.closed>ul{display:none}
.empty{text-align:center;padding:48px 16px;color:var(--muted)}
.hide{display:none}
</style></head><body>
<header>
  <h1>Visor de eventos</h1>
  <span class="muted" id="info">cargando…</span>
  <input id="filter" placeholder="Filtrar (tipo, orderId, estado…)">
  <span class="right">
    <button onclick="setAll(true)">Expandir todo</button>
    <button onclick="setAll(false)">Contraer todo</button>
    <button onclick="purge()">Vaciar cola</button>
    <button class="primary" onclick="load()">Actualizar</button>
  </span>
</header>
<main id="list"></main>
<script>
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function node(v, key){
  const k = key === undefined ? '' : `<span class="k">"${esc(key)}"</span>: `;
  if (v === null) return `<li>${k}<span class="nl">null</span></li>`;
  if (Array.isArray(v) || typeof v === 'object'){
    const arr = Array.isArray(v), entries = arr ? v.map((x,i)=>[i,x]) : Object.entries(v);
    const [o,c] = arr ? ['[',']'] : ['{','}'];
    const inner = entries.map(([kk,vv]) => node(vv, arr ? undefined : kk)).join('');
    return `<li><span class="tg" onclick="this.parentNode.classList.toggle('closed');this.textContent=this.parentNode.classList.contains('closed')?'▸':'▾'">▾</span>${k}${o}<span class="cnt">${entries.length} ${arr?'elementos':'campos'}</span><ul>${inner}</ul>${c}</li>`;
  }
  if (typeof v === 'string') return `<li>${k}<span class="s">"${esc(v)}"</span></li>`;
  if (typeof v === 'number') return `<li>${k}<span class="num">${v}</span></li>`;
  return `<li>${k}<span class="b">${v}</span></li>`;
}
function highlight(o){
  return esc(JSON.stringify(o, null, 2)).replace(/(&quot;(?:[^&]|&(?!quot;))*?&quot;)(\s*:)?|\b(true|false)\b|\bnull\b|-?\d+(?:\.\d+)?/g,
    (m, str, colon, bool) => str ? (colon ? `<span class="k">${str}</span>${colon}` : `<span class="s">${str}</span>`)
      : bool ? `<span class="b">${m}</span>` : m === 'null' ? `<span class="nl">null</span>` : `<span class="num">${m}</span>`);
}
function card(m, i){
  const p = m.payload || {}, d = p.data || {}, tr = d.transition;
  const type = p.eventType || m.routingKey;
  const props = m.properties || {}, h = props.headers || {};
  const amount = d.amount ? `${Number(d.amount.valueMinor).toLocaleString('es-CO')} ${d.amount.currency}` : '';
  return `<div class="card" data-text="${esc(JSON.stringify(m).toLowerCase())}">
  <div class="head" onclick="this.parentNode.querySelector('.det').classList.toggle('hide')">
    <span class="n">#${i+1}</span><span class="type t-${esc(type)}">${esc(type)}</span>
    ${tr ? `<span class="trans">${esc(tr.from ?? '∅')} → <b>${esc(tr.to)}</b></span>` : ''}
    <span class="muted">${esc(amount)}</span>
    <span class="right muted">${esc(p.occurredAt || '')}</span>
  </div>
  <div class="det">
  <div class="meta">
    <div><b>message_id</b> <code>${esc(props.message_id || '')}</code></div>
    <div><b>routing key</b> <code>${esc(m.routingKey)}</code></div>
    <div><b>exchange</b> <code>${esc(m.exchange)}</code></div>
    <div><b>aggregate</b> <code>${esc(h.aggregate_type || '')} ${esc(h.aggregate_id || '')}</code></div>
    <div><b>delivery_mode</b> <code>${esc(props.delivery_mode ?? '')}${props.delivery_mode===2?' (persistente)':''}</code></div>
    <div><b>entregas</b> <code>${esc(h['x-acquired-count'] ?? 0)}</code></div>
  </div>
  <div class="tabs"><button class="tab on" onclick="tab(this,0)">Viewer</button><button class="tab" onclick="tab(this,1)">Text</button>
    <span class="right"><button onclick='copy(this, ${i})'>Copiar JSON</button></span></div>
  <div class="body"><div class="tree"><ul>${node(p)}</ul></div><pre class="hide">${highlight(p)}</pre></div>
  </div></div>`;
}
let data = [];
function tab(btn, n){
  const c = btn.closest('.card'); c.querySelectorAll('.tab').forEach((t,i)=>t.classList.toggle('on', i===n));
  c.querySelector('.tree').classList.toggle('hide', n!==0); c.querySelector('pre').classList.toggle('hide', n!==1);
}
function copy(btn, i){ navigator.clipboard.writeText(JSON.stringify(data[i].payload, null, 2)); btn.textContent='Copiado ✔'; setTimeout(()=>btn.textContent='Copiar JSON',1200); }
function setAll(open){ document.querySelectorAll('.tree li').forEach(li=>{ const t=li.querySelector(':scope>.tg'); if(t){ li.classList.toggle('closed',!open); t.textContent=open?'▾':'▸'; }}); }
function applyFilter(){ const q=document.getElementById('filter').value.toLowerCase(); document.querySelectorAll('.card').forEach(c=>c.classList.toggle('hide', q && !c.dataset.text.includes(q))); }
document.getElementById('filter').addEventListener('input', applyFilter);
async function load(){
  try{
    const r = await fetch('/api/messages'); const j = await r.json();
    if (!r.ok) throw new Error(j.error);
    data = j.messages;
    document.getElementById('info').textContent = `cola ${j.queue} · ${j.total} mensajes · ${new Date().toLocaleTimeString()}`;
    document.getElementById('list').innerHTML = data.length ? data.map(card).join('')
      : '<div class="empty">La cola está vacía. Corre <code>./scripts/demo.sh</code> y pulsa Actualizar.</div>';
    applyFilter();
  }catch(e){ document.getElementById('info').textContent = 'Error: ' + e.message; }
}
async function purge(){ if(confirm('¿Vaciar la cola de demo?')){ await fetch('/api/purge',{method:'POST'}); load(); } }
load();
</script></body></html>"""

if __name__ == "__main__":
    ensure_queue()
    print(f"Visor en http://localhost:{PORT}  (cola {QUEUE}, Ctrl+C para salir)")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
