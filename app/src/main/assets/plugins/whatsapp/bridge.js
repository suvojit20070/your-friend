// Your Friend — Original project by Suvo.
//
// WhatsApp bridge for the Your Friend app: the user's number as a linked device (WhatsApp Web
// multi-device protocol, via Baileys), with a small local HTTP API (127.0.0.1 only, token
// protected) the app calls. Runs in Termux. Chats and recent messages are kept in store.json.

const fs = require('fs');
const path = require('path');
const http = require('http');
const pino = require('pino');
const baileys = require('@whiskeysockets/baileys');
const makeWASocket = baileys.default || baileys.makeWASocket;
const { useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion, Browsers } = baileys;

const DIR = __dirname;
const PORT = Number(process.env.YF_PORT || 8765);
const TOKEN = process.env.YF_TOKEN || '';
const AUTH = path.join(DIR, 'auth');
const STORE = path.join(DIR, 'store.json');

let sock = null;
let state = { connected: false, me: null, registered: false, lastError: '' };
let pairWaiters = [];
let qrSeen = false;

// ---- store: chats + last 100 messages per chat
let store = { chats: {}, messages: {}, contacts: {} };
try { store = Object.assign(store, JSON.parse(fs.readFileSync(STORE, 'utf8'))); } catch (e) { }
let dirty = false;
setInterval(() => { if (dirty) { dirty = false; fs.writeFile(STORE, JSON.stringify(store), () => { }); } }, 15000);

function textOf(m) {
  const msg = m.message || {};
  const inner = msg.ephemeralMessage ? msg.ephemeralMessage.message : msg.viewOnceMessage ? msg.viewOnceMessage.message : msg;
  const x = inner || {};
  if (x.conversation) return x.conversation;
  if (x.extendedTextMessage) return x.extendedTextMessage.text || '';
  if (x.imageMessage) return '[photo] ' + (x.imageMessage.caption || '');
  if (x.videoMessage) return '[video] ' + (x.videoMessage.caption || '');
  if (x.documentMessage) return '[file] ' + (x.documentMessage.fileName || '');
  if (x.audioMessage) return x.audioMessage.ptt ? '[voice message]' : '[audio]';
  if (x.stickerMessage) return '[sticker]';
  if (x.reactionMessage) return '[reaction ' + (x.reactionMessage.text || '') + ']';
  if (x.contactMessage) return '[contact] ' + (x.contactMessage.displayName || '');
  if (x.locationMessage) return '[location]';
  const k = Object.keys(x)[0];
  return k ? '[' + k.replace(/Message$/, '') + ']' : '';
}

function nameOf(jid) {
  const c = store.contacts[jid];
  const ch = store.chats[jid];
  return (ch && ch.name) || (c && (c.name || c.notify || c.verifiedName)) || jid.split('@')[0];
}

function remember(m, live) {
  const jid = m.key && m.key.remoteJid;
  if (!jid || jid === 'status@broadcast') return;
  const t = Number(m.messageTimestamp || Date.now() / 1000);
  const rec = { id: m.key.id, from: m.key.fromMe ? 'me' : (m.pushName || nameOf(m.key.participant || jid)), text: textOf(m), t };
  if (!rec.text) return;
  const list = store.messages[jid] || (store.messages[jid] = []);
  if (list.some(x => x.id === rec.id)) return;
  list.push(rec);
  list.sort((a, b) => a.t - b.t);
  while (list.length > 100) list.shift();
  const ch = store.chats[jid] || (store.chats[jid] = { name: '' });
  if (m.pushName && !m.key.fromMe && !jid.endsWith('@g.us') && !ch.name) ch.name = m.pushName;
  if (!ch.t || t >= ch.t) { ch.t = t; ch.last = rec.text; }
  if (live && !m.key.fromMe) ch.unread = (ch.unread || 0) + 1;
  dirty = true;
}

async function start() {
  const { state: auth, saveCreds } = await useMultiFileAuthState(AUTH);
  let version;
  try { version = (await fetchLatestBaileysVersion()).version; } catch (e) { }
  sock = makeWASocket({
    version, auth, printQRInTerminal: false, logger: pino({ level: 'silent' }),
    browser: Browsers ? Browsers.macOS('Google Chrome') : ['Your Friend', 'Chrome', '1.0'],
    markOnlineOnConnect: false, syncFullHistory: false,
  });
  state.registered = !!auth.creds.registered;
  sock.ev.on('creds.update', saveCreds);
  sock.ev.on('connection.update', (u) => {
    if (u.qr) { qrSeen = true; flushPairing(); }
    if (u.connection === 'open') {
      state.connected = true; state.registered = true;
      state.me = sock.user ? { id: sock.user.id, name: sock.user.name } : null;
    }
    if (u.connection === 'close') {
      state.connected = false;
      const code = u.lastDisconnect && u.lastDisconnect.error && u.lastDisconnect.error.output ? u.lastDisconnect.error.output.statusCode : 0;
      state.lastError = String(u.lastDisconnect && u.lastDisconnect.error ? u.lastDisconnect.error.message : 'closed');
      if (code === DisconnectReason.loggedOut) {
        fs.rmSync(AUTH, { recursive: true, force: true });
        state.registered = false; state.me = null;
      }
      qrSeen = false;
      setTimeout(() => start().catch(e => { state.lastError = String(e); }), 3000);
    }
  });
  sock.ev.on('messaging-history.set', ({ chats, contacts, messages }) => {
    (contacts || []).forEach(c => { store.contacts[c.id] = Object.assign(store.contacts[c.id] || {}, c); });
    (chats || []).forEach(c => {
      const ch = store.chats[c.id] || (store.chats[c.id] = {});
      if (c.name) ch.name = c.name;
      if (c.unreadCount) ch.unread = c.unreadCount;
      if (c.conversationTimestamp) ch.t = Math.max(ch.t || 0, Number(c.conversationTimestamp));
    });
    (messages || []).forEach(m => remember(m, false));
    dirty = true;
  });
  sock.ev.on('contacts.upsert', (cs) => { cs.forEach(c => { store.contacts[c.id] = Object.assign(store.contacts[c.id] || {}, c); }); dirty = true; });
  sock.ev.on('chats.upsert', (cs) => { cs.forEach(c => { const ch = store.chats[c.id] || (store.chats[c.id] = {}); if (c.name) ch.name = c.name; }); dirty = true; });
  sock.ev.on('groups.upsert', (gs) => { gs.forEach(g => { const ch = store.chats[g.id] || (store.chats[g.id] = {}); ch.name = g.subject; }); dirty = true; });
  sock.ev.on('messages.upsert', ({ messages, type }) => { messages.forEach(m => remember(m, type === 'notify')); });
}

// Pairing codes can be asked for once the socket is up (it shows a QR first).
function flushPairing() {
  const w = pairWaiters; pairWaiters = [];
  w.forEach(async ({ number, done }) => {
    try { done(null, await sock.requestPairingCode(number)); } catch (e) { done(e); }
  });
}

function jidFor(to) {
  const s = String(to || '').trim();
  if (s.includes('@')) return s;
  const digits = s.replace(/[^0-9]/g, '');
  if (digits.length >= 7 && digits.length === s.replace(/[\s+()-]/g, '').length) return digits + '@s.whatsapp.net';
  // A name: the one chat / contact that matches.
  const q = s.toLowerCase();
  const all = Object.keys(store.chats).concat(Object.keys(store.contacts));
  const hits = [...new Set(all)].filter(j => nameOf(j).toLowerCase().includes(q));
  const exact = hits.filter(j => nameOf(j).toLowerCase() === q);
  if (exact.length === 1) return exact[0];
  if (hits.length === 1) return hits[0];
  throw new Error(hits.length ? 'Several chats match "' + s + '": ' + hits.slice(0, 8).map(j => nameOf(j) + ' (' + j + ')').join(', ') : 'No chat named "' + s + '" — give the number with country code');
}

function body(req) {
  return new Promise((ok, bad) => {
    let b = ''; req.on('data', c => { b += c; if (b.length > 60e6) req.destroy(); });
    req.on('end', () => { try { ok(b ? JSON.parse(b) : {}); } catch (e) { bad(e); } });
  });
}

http.createServer(async (req, res) => {
  const send = (code, o) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(o)); };
  if (TOKEN && req.headers['x-token'] !== TOKEN) return send(401, { error: 'bad token' });
  const url = new URL(req.url, 'http://x');
  try {
    switch (url.pathname) {
      case '/status':
        return send(200, Object.assign({}, state, { chats: Object.keys(store.chats).length }));
      case '/pair': {
        const { number } = await body(req);
        const n = String(number || '').replace(/[^0-9]/g, '');
        if (!n) return send(400, { error: 'number with country code is required' });
        if (state.registered) return send(200, { already: true, me: state.me });
        const code = await new Promise((ok, bad) => {
          pairWaiters.push({ number: n, done: (e, c) => e ? bad(e) : ok(c) });
          if (qrSeen) flushPairing();
          setTimeout(() => bad(new Error('WhatsApp did not answer in time, try again')), 45000);
        });
        return send(200, { code });
      }
      case '/chats': {
        const limit = Number(url.searchParams.get('limit') || 20);
        const q = (url.searchParams.get('q') || '').toLowerCase();
        const list = Object.entries(store.chats)
          .map(([jid, c]) => ({ jid, name: nameOf(jid), last: c.last || '', time: c.t || 0, unread: c.unread || 0, group: jid.endsWith('@g.us') }))
          .filter(c => !q || c.name.toLowerCase().includes(q) || c.jid.includes(q))
          .sort((a, b) => b.time - a.time).slice(0, limit);
        return send(200, { chats: list });
      }
      case '/messages': {
        const jid = jidFor(url.searchParams.get('to'));
        const limit = Number(url.searchParams.get('limit') || 20);
        const ch = store.chats[jid]; if (ch) { ch.unread = 0; dirty = true; }
        return send(200, { jid, name: nameOf(jid), messages: (store.messages[jid] || []).slice(-limit) });
      }
      case '/send': {
        if (!state.connected) return send(503, { error: 'WhatsApp is not connected: ' + state.lastError });
        const b = await body(req);
        const jid = jidFor(b.to);
        let content;
        if (b.b64) {
          const buf = Buffer.from(b.b64, 'base64');
          const mt = b.mimetype || 'application/octet-stream';
          if (mt.startsWith('image/')) content = { image: buf, caption: b.text || undefined, mimetype: mt };
          else if (mt.startsWith('video/')) content = { video: buf, caption: b.text || undefined, mimetype: mt };
          else if (mt.startsWith('audio/')) content = { audio: buf, mimetype: mt };
          else content = { document: buf, mimetype: mt, fileName: b.filename || 'file', caption: b.text || undefined };
        } else {
          if (!b.text) return send(400, { error: 'text is required' });
          content = { text: b.text };
        }
        const sent = await sock.sendMessage(jid, content);
        remember(sent, false);
        return send(200, { ok: true, jid, name: nameOf(jid), id: sent.key.id });
      }
      case '/read': {
        const b = await body(req);
        const jid = jidFor(b.to);
        const last = (store.messages[jid] || []).filter(m => m.from !== 'me').slice(-1)[0];
        if (last) await sock.readMessages([{ remoteJid: jid, id: last.id, fromMe: false }]);
        const ch = store.chats[jid]; if (ch) { ch.unread = 0; dirty = true; }
        return send(200, { ok: true });
      }
      case '/logout':
        try { await sock.logout(); } catch (e) { }
        fs.rmSync(AUTH, { recursive: true, force: true });
        state.registered = false; state.me = null;
        return send(200, { ok: true });
      default:
        return send(404, { error: 'unknown' });
    }
  } catch (e) {
    return send(500, { error: String(e && e.message || e) });
  }
}).listen(PORT, '127.0.0.1', () => console.log('bridge on', PORT));

start().catch(e => { state.lastError = String(e); console.error(e); });
