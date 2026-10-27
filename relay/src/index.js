// The OpenDraft relay: lets players join a lobby with a short code when nobody can open a port.
//
// The player who creates a lobby still runs the synchronizer in their own game. Their game keeps one
// WebSocket open to /host, and each joining player opens one to /join/<code>. The relay only passes
// lines between them, so all of the game's rules stay in the mod:
//
//   relay -> host     ROOM <code> [<key>]  (once, right after connecting; the key only for /host?v=2)
//   relay -> host     OPEN <id>            (a player joined through the relay)
//   relay -> host     DATA <id> <line>     (a line from that player)
//   relay -> host     CLOSE <id>           (that player left)
//   host -> relay     DATA <id> <line>     (a line for that player)
//   host -> relay     CLOSE <id>           (disconnect that player)
//   host -> relay     BYE                  (the lobby is closing for good)
//
// Getting the code back: a host that asks for /host?v=2 also gets a secret key. If its connection drops
// (without BYE), the code is kept for HOLD_MS, and /host/<code> with the key in the X-OpenDraft-Key header
// gets it back. Meanwhile the players are disconnected with code 4003, and their games keep trying
// /join/<code> until the host is back. Without the key, nobody can take the code, so a player who knows it
// can't pose as the host. Hosts that don't ask for v=2 (older versions of the mod) work exactly as before:
// when they go, the lobby closes (code 4000).
//
// Each lobby is one Durable Object, named by its code. It uses the WebSocket Hibernation API, so it
// sleeps between messages and stays within Cloudflare's free plan.

import { DurableObject } from "cloudflare:workers";

const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // no I, L, O or U
const CODE_LENGTH = 8;
const MAX_MESSAGE = 20 * 1024; // a little over the mod's longest line, plus "DATA <id> "
const MAX_PLAYERS = 32;
const HOLD_MS = 5 * 60 * 1000; // how long a dropped host's code is kept for it
const KEY_HEADER = "X-OpenDraft-Key";
const LOBBY_CLOSED = 4000; // the mod treats this one as "don't reconnect"
const HOST_RECONNECTING = 4003;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("OpenDraft relay. Players connect to it from the game.\n");
    }
    if (url.pathname === "/host") {
      // A fresh random code, retried in the unlikely case it's already in use.
      const version = url.searchParams.get("v") === "2" ? "2" : "1";
      for (let i = 0; i < 5; i++) {
        const code = randomCode();
        const response = await lobby(env, code).fetch(forLobby(request, "host", code, version));
        if (response.status !== 409) return response;
      }
      return new Response("No free lobby code, try again", { status: 503 });
    }
    const rehost = url.pathname.match(/^\/host\/([0-9A-Za-z-]{1,40})$/);
    if (rehost) {
      const code = normalize(rehost[1]);
      if (!code) return new Response("No lobby with that code", { status: 404 });
      return lobby(env, code).fetch(forLobby(request, "rehost", code, "2"));
    }
    const match = url.pathname.match(/^\/join\/([0-9A-Za-z-]{1,40})$/);
    if (match) {
      const code = normalize(match[1]);
      if (!code) return new Response("No lobby with that code", { status: 404 });
      return lobby(env, code).fetch(forLobby(request, "join", code, "1"));
    }
    return new Response("Not found", { status: 404 });
  },
};

export class Lobby extends DurableObject {
  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get("role");
    const code = url.searchParams.get("code");
    const host = this.host();

    if (role === "host") {
      // A code held for a host that dropped is still taken.
      if (host || (await this.held())) return new Response("Code in use", { status: 409 });
      let room = "ROOM " + code;
      if (url.searchParams.get("v") === "2") {
        const key = randomKey();
        await this.ctx.storage.deleteAll();
        await this.ctx.storage.put("key", await sha256(key));
        room += " " + key;
      }
      return this.accept("host", room);
    }

    if (role === "rehost") {
      const stored = await this.ctx.storage.get("key");
      if (!stored || !(await this.held()) && !host) return new Response("No lobby with that code", { status: 404 });
      const key = request.headers.get(KEY_HEADER) || "";
      if ((await sha256(key)) !== stored) return new Response("Not this lobby's host", { status: 403 });
      // The key proves it's the same host. Its old connection may not have closed yet (it died without
      // closing), so it's replaced, and players are sent off to reconnect through the new one.
      if (host) {
        try {
          host.close(1000, "Replaced by a new connection");
        } catch {}
      }
      this.closePlayers(HOST_RECONNECTING, "The lobby's host reconnected");
      await this.ctx.storage.delete("abandoned");
      await this.ctx.storage.deleteAlarm();
      return this.accept("host", "ROOM " + code + " " + key);
    }

    // role === "join"
    if (!host) return new Response("No lobby with that code", { status: 404 });
    if (this.ctx.getWebSockets("player").length >= MAX_PLAYERS) {
      return new Response("The lobby is full", { status: 403 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    const id = crypto.randomUUID().slice(0, 8);
    this.ctx.acceptWebSocket(server, ["player", "p:" + id]);
    server.serializeAttachment({ id });
    send(host, "OPEN " + id);
    return new Response(null, { status: 101, webSocket: client });
  }

  accept(tag, first) {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [tag]);
    server.send(first);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > MAX_MESSAGE) {
      ws.close(4002, "Message too large");
      return;
    }
    if (this.ctx.getTags(ws).includes("host")) {
      if (ws.readyState !== WebSocket.OPEN) return; // a replaced connection, being closed
      const [kind, id, line] = split(message);
      if (kind === "BYE") {
        // Closing on purpose: forget the key now rather than holding the code.
        await this.ctx.storage.deleteAll();
        await this.ctx.storage.deleteAlarm();
        return;
      }
      const player = this.ctx.getWebSockets("p:" + id)[0];
      if (!player) {
        if (kind === "DATA") send(ws, "CLOSE " + id);
      } else if (kind === "DATA") {
        send(player, line);
      } else if (kind === "CLOSE") {
        player.close(4001, "Disconnected by the lobby");
      }
      return;
    }
    const host = this.host();
    const { id } = ws.deserializeAttachment();
    if (host) send(host, "DATA " + id + " " + message);
  }

  async webSocketClose(ws) {
    await this.gone(ws);
  }

  async webSocketError(ws) {
    await this.gone(ws);
  }

  async gone(ws) {
    if (this.ctx.getTags(ws).includes("host")) {
      const replaced = this.ctx.getWebSockets("host").some((h) => h !== ws && h.readyState === WebSocket.OPEN);
      if (!replaced) {
        if (await this.ctx.storage.get("key")) {
          // A host that can get the code back: keep it for a while, and send the players off to reconnect.
          await this.ctx.storage.put("abandoned", Date.now());
          await this.ctx.storage.setAlarm(Date.now() + HOLD_MS);
          this.closePlayers(HOST_RECONNECTING, "The lobby's host is reconnecting");
        } else {
          this.closePlayers(LOBBY_CLOSED, "The lobby closed");
        }
      }
    } else {
      const host = this.host();
      const attachment = ws.deserializeAttachment();
      if (host && attachment) send(host, "CLOSE " + attachment.id);
    }
    try {
      ws.close(1000, "Bye");
    } catch {}
  }

  // The host didn't come back in time: the code is free again.
  async alarm() {
    if (!this.host()) {
      await this.ctx.storage.deleteAll();
      this.closePlayers(LOBBY_CLOSED, "The lobby closed");
    }
  }

  closePlayers(code, reason) {
    for (const player of this.ctx.getWebSockets("player")) {
      try {
        player.close(code, reason);
      } catch {}
    }
  }

  // Whether the code is being kept for a host that dropped.
  async held() {
    const abandoned = await this.ctx.storage.get("abandoned");
    return abandoned !== undefined && Date.now() - abandoned < HOLD_MS;
  }

  host() {
    return this.ctx.getWebSockets("host").find((ws) => ws.readyState === WebSocket.OPEN);
  }
}

function lobby(env, code) {
  return env.LOBBY.get(env.LOBBY.idFromName(code));
}

function forLobby(request, role, code, version) {
  return new Request("https://lobby/?role=" + role + "&code=" + code + "&v=" + version, request);
}

function randomCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  return Array.from(bytes, (b) => ALPHABET[b & 31]).join("");
}

function randomKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

function normalize(text) {
  const code = text.toUpperCase().replaceAll("-", "").replaceAll("O", "0").replaceAll("I", "1").replaceAll("L", "1");
  if (code.length !== CODE_LENGTH) return null;
  for (const c of code) if (!ALPHABET.includes(c)) return null;
  return code;
}

// "DATA <id> <line>" -> ["DATA", id, line]
function split(message) {
  const a = message.indexOf(" ");
  if (a < 0) return [message, "", ""];
  const b = message.indexOf(" ", a + 1);
  if (b < 0) return [message.slice(0, a), message.slice(a + 1), ""];
  return [message.slice(0, a), message.slice(a + 1, b), message.slice(b + 1)];
}

function send(ws, text) {
  try {
    ws.send(text);
  } catch {}
}
