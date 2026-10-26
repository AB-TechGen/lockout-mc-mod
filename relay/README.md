# OpenDraft relay

A small program that lets players join a lobby with a short code from anywhere, without opening any ports. It
runs on Cloudflare's free plan. The synchronizer still runs in the host's game; the relay only passes the game's
lines (names, claims, chat) between the host and the players and stores nothing.

The mod already uses OpenDraft's relay, so players don't need anything here. These steps are for running your own.

## Putting it online (once)

You need a free [Cloudflare account](https://dash.cloudflare.com/sign-up) and [Node.js](https://nodejs.org) (the
LTS version). Then, in Terminal:

```
cd path/to/opendraft/relay
npx wrangler login
npx wrangler deploy
```

`wrangler login` opens your browser to let it use your Cloudflare account. The first `deploy` may ask you to pick a
`workers.dev` subdomain; any name works. When it finishes, it prints the relay's address, like
`https://opendraft-relay.yourname.workers.dev`. Open it in a browser to check: it says "OpenDraft relay".

To use it, put that address in `DEFAULT_URL` in `src/main/java/dev/opendraft/net/Relay.java` and build the mod.
To try a relay without rebuilding, add `relay=<address>` to `config/opendraft.properties` in the Minecraft folder.

For a spare, deploy a second copy under another name with `npx wrangler deploy --name opendraft-relay-2` and list
both addresses in `DEFAULT_URL`, separated by commas. New lobbies open on the first relay that answers, and joining
asks every relay for the code. Copies on one Cloudflare account share the same free daily limit, so a spare helps if
one copy breaks, not with more players.

Run `npx wrangler deploy` again after changing anything in this folder. Players in a lobby at that moment are
disconnected and have to join again.

## Free plan limits

Cloudflare's free plan allows 100,000 requests a day for the relay and 100,000 for its lobbies. Each player joining
a lobby is one request, and every 20 lines a game sends count as one more, so a full match uses a few hundred at
most. Lobbies sleep between messages, so an idle lobby costs nothing. If a day's limit runs out, new players can't
join until it resets at midnight UTC.

## How it works

Creating a lobby opens a WebSocket to `/host`, and the relay answers with a code. Each player who joins opens one to
`/join/<code>`. Every lobby is a Durable Object named by its code; `src/index.js` has the details.
