# CDisc

Music discs for Spigot, Paper and Folia that play real audio through a voice-chat
mod, audible to everyone standing near the jukebox.

## Playing something in the first minute

CDisc needs no keys, no accounts and no token setup to play music. Install it,
then use the folder it made for you:

1. Install [Simple Voice Chat](https://modrepo.de/minecraft/voicechat/downloads)
   or [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice) — CDisc has no
   sound of its own, it plays through these. Install both and it plays through
   both at once, so players on either mod hear the music.
2. Drop `CDisc.jar` into `plugins/` and start the server.
3. Put an audio file into `plugins/CDisc/local/`.
4. In game, holding any music disc:

```
/cdisc create local:yourfile.mp3
```

Press Tab after `local:` and the server lists what's actually in the folder.
Put the disc in a jukebox and it plays.

That path touches no external service, so nothing about it can be rate limited,
signed out, or refused. If you only ever use the music folder, you are done —
the rest of this file is about reaching things that aren't on your disk.

### Getting a file in without touching the server's filesystem

```
/cdisc admin download https://example.com/song.mp3
/cdisc admin download https://www.youtube.com/watch?v=...
/cdisc admin download https://open.spotify.com/track/...
/cdisc admin download daft punk around the world
```

It takes whatever `/cdisc create` takes and keeps the result: a direct link is
fetched as it stands, a YouTube link is resolved the way playback resolves it —
straight off YouTube, through SABR, or through the backend when the video wants
an account — and a Spotify link is matched on its ISRC to the recording the
label uploaded. A search shows the same clickable list `/cdisc create` shows,
and the one you pick is the one saved.

The file lands in the music folder and behaves exactly like one you copied
there yourself. Operators only by default; the size ceiling and the whole
feature are in `sources.yml` under `local.download`.

Links into the server's own network are refused. A URL here is an instruction
to *the server* to make a request, so `localhost` and the LAN are not fair
game, whoever typed it.

## When something doesn't play

```
/cdisc doctor
```

It lists every source with one of four marks: works, switched off, switched on
but missing its key, or works-until-it-doesn't. Run it from the console too.
Most "it just says track not found" reports are a source that was never
configured, and this is the command that says which.

## The other sources

| Source | Prefix | Needs |
| --- | --- | --- |
| Server's own files | `local:` | nothing |
| SoundCloud | `sc:` | nothing |
| Direct links | — | `http: true` in `sources.yml`, or use `/cdisc admin download` |
| Spotify | `sp:` | client id + secret in `tokens.yml` |
| Yandex Music | `ym:` | access token |
| VK Music | `vk:` | user token |
| YouTube | `yt:` | nothing to start; a po-token in practice |
| Discord attachments | — | nothing (links expire after about a day) |
| Twitch, TikTok, Reddit, Mixcloud | — | nothing |

A bare query with no prefix is searched on YouTube.

### About YouTube

YouTube decides what to serve by the address asking. A server it doesn't
recognise is refused however correctly it asks, and the refusals are not
obviously about the address: clients fail one after another with six
different messages — "requires login", HTTP 400, no audio formats, "the page
needs to be reloaded", 403 on the stream itself.

**On by default:**

```yaml
# sources.yml
youtube:
  fallback-api: true
```

The backend asks from its own address, and mints its own proof-of-origin
token while doing it. Nothing else needs configuring — no po-token, no
visitor-data, no keys. Both paths start at once and whichever answers first
wins, so it costs nothing where YouTube already works. What it does cost is a
dependency on someone else's machine.

CDisc switches it on once at startup, and on a server located in Russia also
switches on `proxy`, since YouTube is slowed there. Set either back to `false`
and it stays that way.

If the backend isn't an option, the rest of this section is about supplying a
po-token yourself. It is a good deal more work.

Three ways, worst to best:

- **Nothing.** YouTube works sometimes. Everything else still works always.
- **Paste a pair by hand** into `tokens.yml` — `po-token` and `visitor-data`,
  which only work together. The file explains where to find them. You will be
  doing this again in a few weeks.
- **Use a token server.** A small Node service that mints a fresh pair in a
  headless browser. Point `youtube.po-token-backend.url` in `sources.yml` at
  your own, or leave the public one that ships there, and CDisc keeps itself
  supplied.

## Configuration

Three files, and the split is deliberate — two are safe to share, one is not.

| File | Holds |
| --- | --- |
| `config.yml` | how the plugin behaves — voice backend, jukeboxes, lyrics |
| `sources.yml` | which services it may play from |
| `tokens.yml` | API keys and tokens — **treat as a password** |
| `permissions.yml` | who may do what, and the limits on players who aren't staff |

`/cdisc reload` re-reads all of them.

An upgrade rewrites each of these from the copy in the new jar: new settings
arrive with their defaults, the notes beside them are refreshed, and anything
you changed by hand stays changed. The previous file is kept as `<name>.old`.

### Languages

Arabic, English, German, Hebrew, Russian, Spanish and Ukrainian ship with the
plugin, and each player reads the one their client is set to. Pin one for the
whole server with `language` in `config.yml`, or drop your own
`languages/<name>.yml` into the plugin folder and name it there — keys you
leave out fall back to English.

Hebrew and Arabic are written right to left, which Minecraft's chat does not
lay out and whose Arabic letters it does not join. The words are correct; the
rendering is the game's.

## Building

Needs JDK 21.

```bash
mvn -DskipTests package
```

The jar lands in `target/`.

## Licence

See [LICENSE](src/main/resources/LICENSE).
