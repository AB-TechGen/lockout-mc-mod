// The OpenDraft relay: lets players join a lobby with a short code when nobody can open a port.
//
// The player who creates a lobby still runs the synchronizer in their own game. Their game keeps one
// WebSocket open to /host, and each joining player opens one to /join/<code>. The relay only passes
// lines between them, so all of the game's rules stay in the mod:
//
//   relay -> host     ROOM <code>          (once, right after connecting)
//   relay -> host     OPEN <id>            (a player joined through the relay)
//   relay -> host     DATA <id> <line>     (a line from that player)
//   relay -> host     CLOSE <id>           (that player left)
//   host -> relay     DATA <id> <line>     (a line for that player)
//   host -> relay     CLOSE <id>           (disconnect that player)
//
// Each lobby is one Durable Object, named by its code. It uses the WebSocket Hibernation API, so it
// sleeps between messages and stays within Cloudflare's free plan.

import { DurableObject } from "cloudflare:workers";

const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // no I, L, O or U
const CODE_LENGTH = 8;
const MAX_MESSAGE = 20 * 1024; // a little over the mod's longest line, plus "DATA <id> "
const MAX_PLAYERS = 32;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("OpenDraft relay. Players connect to it from the game.\n");
    }
    if (url.pathname === "/host") {
      // A fresh random code, retried in the unlikely case it's already in use.
      for (let i = 0; i < 5; i++) {
        const code = randomCode();
        const response = await lobby(env, code).fetch(forLobby(request, "host", code));
        if (response.status !== 409) return response;
      }
      return new Response("No free lobby code, try again", { status: 503 });
    }
    const match = url.pathname.match(/^\/join\/([0-9A-Za-z-]{1,40})$/);
    if (match) {
      const code = normalize(match[1]);
      if (!code) return new Response("No lobby with that code", { status: 404 });
      return lobby(env, code).fetch(forLobby(request, "join", code));
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
    if (role === "host" && host) return new Response("Code in use", { status: 409 });
    if (role === "join" && !host) return new Response("No lobby with that code", { status: 404 });
    if (role === "join" && this.ctx.getWebSockets("player").length >= MAX_PLAYERS) {
      return new Response("The lobby is full", { status: 403 });
    }

    const [client, server] = Object.values(new WebSocketPair());
    if (role === "host") {
      this.ctx.acceptWebSocket(server, ["host"]);
      server.send("ROOM " + code);
    } else {
      const id = crypto.randomUUID().slice(0, 8);
      this.ctx.acceptWebSocket(server, ["player", "p:" + id]);
      server.serializeAttachment({ id });
      send(host, "OPEN " + id);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > MAX_MESSAGE) {
      ws.close(4002, "Message too large");
      return;
    }
    if (this.ctx.getTags(ws).includes("host")) {
      const [kind, id, line] = split(message);
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
    this.gone(ws);
  }

  async webSocketError(ws) {
    this.gone(ws);
  }

  gone(ws) {
    if (this.ctx.getTags(ws).includes("host")) {
      for (const player of this.ctx.getWebSockets("player")) {
        try {
          player.close(4000, "The lobby closed");
        } catch {}
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

  host() {
    return this.ctx.getWebSockets("host").find((ws) => ws.readyState === WebSocket.OPEN);
  }
}

function lobby(env, code) {
  return env.LOBBY.get(env.LOBBY.idFromName(code));
}

function forLobby(request, role, code) {
  return new Request("https://lobby/?role=" + role + "&code=" + code, request);
}

function randomCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  return Array.from(bytes, (b) => ALPHABET[b & 31]).join("");
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
