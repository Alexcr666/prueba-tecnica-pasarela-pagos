// Estado del entorno de la prueba. GET solo consulta; POST además enciende el Codespace si está apagado.
// Variables (en Vercel): GH_TOKEN (token de grano fino, solo "Codespaces" de este repositorio),
// CODESPACE_NAME y CONSOLE_URL. El token nunca llega al navegador.
const API = 'https://api.github.com/user/codespaces/';

async function github(path, method = 'GET') {
  return fetch(API + process.env.CODESPACE_NAME + path, {
    method,
    headers: {
      Authorization: `Bearer ${process.env.GH_TOKEN}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'lanzador-pasarela-pagos',
    },
  });
}

async function consoleHealth(url) {
  try {
    const r = await fetch(url + '/api/health', {signal: AbortSignal.timeout(6000), redirect: 'manual'});
    if (!r.ok) return null;
    return await r.json();
  } catch {
    return null;
  }
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  const consoleUrl = process.env.CONSOLE_URL;
  try {
    const r = await github('');
    if (!r.ok) return res.status(502).json({error: `GitHub respondió ${r.status}`});
    let {state} = await r.json();
    let started = false;
    if (req.method === 'POST' && (state === 'Shutdown' || state === 'Failed')) {
      const s = await github('/start', 'POST');
      started = s.ok;
      if (s.ok) state = 'Starting';
    }
    const health = state === 'Available' ? await consoleHealth(consoleUrl) : null;
    const ready = Boolean(health && health.service && health.broker && health.gateway);
    return res.status(200).json({state, started, ready, health, url: consoleUrl});
  } catch (e) {
    return res.status(500).json({error: String(e.message || e)});
  }
}
