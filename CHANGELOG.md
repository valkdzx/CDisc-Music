# Changelog

## 2.0.2

- 📯 Goat horns record sounds: `/cdisc create` with a horn in hand, then blow it to play the sound through voice chat. Up to 15 seconds by default (`goat-horn` in config.yml).
- 🔀 YouTube asks one source at a time: the direct read first, then the backend, youtube-source last. A track costs the backend fewer requests, and YouTube links work on servers where youtube-source is blocked, as searches already did. The console names the source each track came from.
- 🎯 The direct read no longer depends on `youtube.sabr`: it is always on, and `sabr` only adds reading SABR streams when it fails.
- 🌍 `proxy.address` in sources.yml sends every request the plugin makes through an HTTP proxy. Its login goes in tokens.yml.
- 🗑️ `/cdisc admin ytsetup` is gone: the Google login only helped youtube-source, which is now asked last.

## 2.0.1

- ☁️ SoundCloud can play through the backend (`soundcloud.proxy` in sources.yml) where SoundCloud refuses to load tracks. It switches on by itself on servers in Russia; turning it off is respected. Playlists and `sc:` search still go to SoundCloud directly.
- ⚙️ A settings window: `/cdisc admin config` edits config.yml, sources.yml, permissions.yml and tokens.yml in a dialog, one tab per file (Paper 1.21.7+). Off by default (`config-dialog` in config.yml). Needs `cdisc.admin.config`, which only operators have by default and which also grants everything `cdisc.admin` does.
- 📝 A new lyrics hologram takes its preset size from the first line.

## 2.0

- ⏩ Seeking in long YouTube videos is now instant.
- 🔇 A failed audio frame no longer silences a jukebox.
- 📜 Spotify playlists and albums work without keys; YouTube links with a playlist are read as playlists.
- 🎧 Spotify search without keys falls back to YouTube.
- 📁 Local files with non-Latin names play correctly.
- ⬆️ New releases download and install themselves when the server stops.
- 🌐 The backend is on by default, and the proxy turns on by itself on servers in Russia. Turning either off is respected.
- 🔁 A disc taken out of the queue no longer keeps playing from the jukebox as a copy.

## 1.9

### 🔑 A permission for every action, and screens built from them

A player either had the player screen or did not. `cdisc.player` opened all of
it at once: pause, skip, seek, volume, the queue, and picking the jukebox up to
carry it away. Half of it could not be handed out.

Every action now carries a node of its own — `cdisc.player.pause`,
`cdisc.queue.add`, `cdisc.lyrics.toggle`, thirty-eight of them — and
permissions.yml holds one rule per action instead of one default per command:

```yml
actions:
  player:
    pause: [somegroup.admin, somegroup.mod]
    portable: op
    repeat: '!somegroup.muted'
    beacon: false
```

A rule is `true`, `false`, `op`, `notop`, the name of a permission, the name of
one with a `!` in front for whoever has *not* got it, or a list of any of those,
and one matching line in the list is enough. `true`, `false` and `op` are set as
the default of the node itself, so a permission plugin can still overrule them
for a single player; a rule that names other rights is a second way in rather
than a way to lose one.

The screens are drawn from the same answers. Someone who may not carry a
jukebox is not shown the button for it, someone who may not change the volume
does not get the slider in the dialog, and a queue that may be read but not
edited refuses the disc rather than swallowing it. The commands answer per
subcommand, so `/cdisc player pause` and `/cdisc player next` are separate
questions.

Nothing has to be rewritten by hand: the old `defaults` block is carried onto
the new rules the first time the plugin starts, and the old node names keep
working as parents over the actions they used to cover, so a grant of
`cdisc.player` in LuckPerms still reaches all of its pieces.

### 🎨 Everyone reads the hologram their own way

A preset was meant to be personal, and a player who set one did get an extra
hologram drawn to their own taste — but that copy was never hidden from anybody
else. Two players with presets at the same jukebox each read three sets of words
at once: their own, the other's, and the jukebox's, stacked in different colours
over the same block.

Every reader now sees the words their own preset asks for and nothing else. One
hologram is put up per look actually in use among the players within 80 blocks
of the jukebox, so a crowd that never touched `/cdisc preset` still shares a
single entity between them, and each hologram is invisible to everyone outside
its own group. Nobody nearby means nothing is spawned at all. The words over the
head of a player carrying a jukebox follow the same rule: bystanders read them in
their own colours and line counts while the carrier reads the sidebar.

Fade length and slide come from the preset now, like every other setting, and
are no longer greyed out in the screen: they used to be the jukebox's to set,
because one hologram had to animate one way for the whole crowd.

**The look stored on a jukebox is gone.** It was the one place where a style
could be put on everybody else's screen, which is the thing this release stops.
Right-clicking the lyrics paper opens your own preset instead, the "Put my look
on this jukebox" button and the `cdisc.lyrics.look` permission are removed — the
node is named in the console when `permissions.yml` is brought up to date — and a
style already written into a jukebox block is ignored. `config.yml` keeps its
part: it is the look a player reads until they change it.
### 🚪 A carried jukebox kept playing on the other side of a portal

Walk into the Nether with a jukebox in hand and the music was gone. The carrier
still heard it, since what reaches the person holding one is a channel of their
own, but everyone else hears it from an invisible entity the jukebox is tied to,
and an entity does not follow a player into another world. It stayed behind: put
the jukebox down over there and nobody heard a thing, pick it up again and only
the carrier did.

That entity is now raised again where the carrier is standing and the sound is
built around the new one, with the volume, the distance, the speaker settings
and the carrier channel put back on it. The old one is closed only once the new
one is playing, so the track does not stop while they are swapped.

### 💬 The words over a carrier stopped trailing behind them

The words above someone carrying a jukebox were moved once every five ticks
while the sound they belong to follows every tick, so the text lagged a step
behind the player and swung back into place a moment later. It now follows on
the same beat as the sound, and it stands perfectly still while the carrier
does.

### ⏱ CDisc stopped taking a sixth of the server thread

A profile of a running server put the plugin at 15.8% of the main thread with a
handful of jukeboxes playing. Nearly all of it was work that did not need doing.

Asking a block a question in Bukkit is not a read, it is a snapshot: the whole
block entity is written out to NBT and parsed back. Two of those were happening
for every playing jukebox every few ticks, one to ask whether the words are
shown over it and one for the speaker settings. Both answers are now read once
and kept until they change, and both are dropped when the jukebox stops or the
plugin is reloaded. The hologram pass also leaves early when nothing is playing
at all.

Following a player who carries a jukebox cost about as much again. Every tick it
asked whether the carrier still has the thing by copying the whole inventory out
and reading the data of every jukebox in it to compare an id; the handle is now
looked for in the slot it was found in last time, and the inventory is swept
only when it is not there, which is the tick it gets dropped, stashed or handed
over. The entity that carries the sound was teleported every tick whether or not
the player had moved, and a teleport is a tracker update for everyone nearby, so
it now moves only when the carrier does.

The same server afterwards, with all of that running at once: 0.8% of the main
thread, about a twentieth of a core. The two profiles are not the same minute of
play, so read the shape of it rather than the exact ratio.


### 🔴 YouTube live streams stopped after five seconds

A live broadcast played for exactly five seconds and then stopped, with nothing
in the console to say why.

Live has no length and no size: it is handed out as a chain of five-second
segments, and the address in the player response serves the one segment that is
current. CDisc read that address the way it reads an ordinary video, got its
five seconds, reached the end of the stream and finished the track. As far as
the player was concerned the song was over, so there was nothing to report.

Live is now recognised as live and read segment by segment for as long as the
broadcast runs. The address is fetched afresh on every start, so a stream that
drops comes back on a current link rather than an expired one, and the audio is
taken from an MP4 format because that is what the segment reader can parse. The
track is finally marked as a stream, which brings with it everything the plugin
already does for streams: reconnection, `LIVE` in place of a running time, no
downloading, and the `allow-live` permission.

Writing a disc from an ordinary YouTube video could fail outright on servers
that check a track before writing it: the check plays a copy, and a track read
straight from YouTube could not be copied. It can now.

### 🟣 Twitch channels loaded but played nothing

A channel came up with its title and its name on the disc, and then silence.

Two things were wrong. Links to twitch.tv were being sent to an address on the
backend that answers 404 for every channel, live or not. And Twitch has since
moved its streams to fragmented MP4, which the library's reader, written for
MPEG-TS, finds no audio in.

Twitch links now go to the plugin's own reader. It takes the initialisation
segment named in the stream's playlist, which is where the description of the
audio lives, plays the fragments that follow it, and falls back to the old
MPEG-TS path for a channel that is still served that way. Choosing the quality
and waiting on new segments is left to the library, which is sound.

### 🔊 Both voice plugins at once

A server with Simple Voice Chat and Plasmo Voice both installed had to pick
one, and CDisc refused to start until it was told which. That was the wrong
question: the two mods' users are different people, so choosing a backend was
choosing which half of the server heard nothing.

Both are now used together. One jukebox is still decoded once, and a source is
opened in every installed voice plugin, so whichever mod a player brought, the
music arrives. `voice-backend: auto` does this by itself when both are present;
`multi` asks for it explicitly, and naming `simplevoicechat` or `plasmovoice`
still pins it to one.

Players who have *both* mods installed would hear the track twice, a few
milliseconds apart — a flanging smear worse than either feed alone. So each
listener is handed to a single backend and filtered off the others, reworked
once a second as people connect and disconnect their voice mod. Nobody is
filtered off a backend unless another one is already reaching them.

Simple Voice Chat starts later than Plasmo does, on its own startup event, so
it joins a broadcast that may already be playing: anything mid-track gains its
Simple Voice Chat source on the spot, with the distance, speaker settings and
carrier it missed replayed onto it.

The cost is one Opus encode per playing jukebox per voice plugin. The track
itself is decoded once regardless.

### 🎤 Lyrics stopped piling up over the jukebox

Leave a song playing, walk away, come back, and the words over the jukebox were
there twice. Then three times, then six: a stack of frozen lines from different
points in the track, overlapping into an unreadable block that nothing short of
a restart would take down.

A display entity reports itself invalid the moment the server stops ticking the
chunk it stands in, which is what a jukebox's chunk does as soon as the last
person walks far enough off. The hologram read that as "gone" and put up a
replacement over the top of the one still hanging there — and read it the same
way when the time came to take a hologram down, and so took nothing down. Either
mistake alone leaves one more copy per trip away and back, for as long as the
track keeps going round.

It now asks whether the display has actually been removed rather than whether it
happens to be ticking, takes the old one down before putting any replacement up,
and once a minute looks over the jukeboxes that are playing for words it is no
longer holding on to and clears them. The same mistake was in the words above a
carried jukebox and in the sample shown while a hologram's colours are being
chosen; both go with it.

## 1.8.1-hotfix-1

### 💿 Discs stopped going missing out of jukeboxes

A jukebox's queue lived in memory and nowhere else. The world stores the one
disc physically shown inside the block; the other 41 cells existed only for as
long as the server was up, so a restart — or a crash, or a `/reload` — deleted
every disc anybody had loaded into a jukebox, with nothing to get them back
from. Nobody watching it happen could have guessed the cause: the discs were
put somewhere the plugin itself displayed them, and they were gone the next
morning.

Queues are written to `plugins/CDisc/queues.yml` now, next to the file that
already remembered what was playing. They are saved a couple of seconds after
anything changes and again on the way out, and read back the first time
something asks after a jukebox — so a queue comes home when a player walks up
to it, rather than everything at once on startup. A jukebox that was mid-track
picks its queue back up along with its position, without the disc it was
playing turning into two.

Three other ways a disc could disappear are closed with it:

- **Fire.** A jukebox burns, and until now nothing noticed: the block simply
  stopped existing and its queue was orphaned in memory, every disc in it gone
  without a drop. Burning one now leaves a jukebox on the ground holding what
  was inside it, the same as breaking it does.

- **A break somebody else refused.** The break handler ran at normal priority
  and ignored cancellation, so a protection plugin turning the break down still
  left the queue drained, the record cleared, and a jukebox full of the owner's
  discs lying on the ground for whoever was standing there. It now runs last
  and not at all on a refused break.

- **Queues nobody had emptied.** Every jukebox anyone had ever opened stayed in
  memory for the life of the server. Empty ones are let go now, unless somebody
  is looking at that jukebox's screens.

### 🔁 And the ways a disc could become two

A hopper under a jukebox empties it — a jukebox has been a container since
1.21 — and only a right-click ever told the queue that its disc had physically
left. The disc went into the hopper and stayed in the queue as well, so
breaking the jukebox afterwards dropped a second one. No timing and no trick
involved: a hopper, a jukebox, and time.

Double-clicking a disc in your own inventory gathers every matching stack in
the window onto the cursor, the queue cells included. That is the one way into
those cells that never arrived as a click on one, so nothing wrote the change
back — the disc left the screen while the queue still held it, and the next
repaint put it back into the cell it had just come out of.

### 🎵 The playlist screen no longer eats discs on a reload

Blank discs handed to the playlist writer are held by the screen rather than
left in the window, because it pages. They were held in memory alone, so a
`/reload` or a shutdown with that screen open destroyed every one of them: by
the time the player is kicked, the close that hands them back has nobody left
to run it. The screens are shut on the way out now, which returns them the
ordinary way. The discs are also handed over a tick after the window closes
rather than during it, so a client tearing that window down doesn't miss them.

### 📻 Carried jukeboxes

A carry that ended with no item to fold its discs into simply discarded them,
and it takes no unusual settings to end one that way — logging out with the
jukebox on your cursor rather than in a slot is enough, because the search for
it only ever looked at inventory slots. The discs come back as a jukebox at the
carrier's feet now, or where it was picked up from if they have already gone.

Servers that raise `portable-jukebox.max-per-player` above 1 had two more.
Putting one jukebox down dropped *every* carry its owner had, leaving the
others as items nothing answered to — never settled, never handed back. Logging
out or dying settled only the most recently picked up, and left the rest
playing to an empty world.

Shuffle is kept when a carried jukebox goes back down somewhere else — it was
the one setting the move forgot.

### 🔇 A jukebox that went quiet for no reason

Right-clicking a jukebox while holding a *carried* jukebox marked the block as
holding one of our discs — the carry handle is a jukebox item with CDisc data
on it, and the check asked whether the item had that data without asking
whether it was a disc at all. Nothing went in, but the mark stayed, and the
next ordinary record put in that jukebox played silently. There is one disc
check in the plugin now instead of two with the same name and opposite
meanings, and it asks both halves of the question.

## 1.8.1

### 💾 `/cdisc admin download` takes what `/cdisc create` takes

It used to accept a direct link to an audio file and nothing else, which left
the one thing an operator actually wants to keep — the track a player just made
a disc of — as the one thing it could not fetch.

It now takes the same queries disc creation does, and resolves them the same
way, because it is the same call: a **YouTube link** goes straight off YouTube
where that works, through SABR where it does not, and through the backend for
the videos YouTube wants an account for — an age gate is a download like any
other now. A **Spotify link** is matched on its ISRC to the recording the label
uploaded, rather than to whatever a title search ranks first. A **search** shows
the same clickable list, and the line you pick is the file you get. A direct
link to a file is still fetched exactly as it was, name after it and all.

The file is named after the track — `Artist - Title` — and its extension comes
from what actually arrives, since the same recording is webm off one road and
m4a off another. Live streams are refused: there is no end to download to.

The console can answer a search now as well as start one, since `/cdisc admin`
runs there and a list you cannot pick from is not an offer. It prints the same
numbered lines and takes `/cdisc pick <number>`.

A download arrives in about a second rather than in the time the track lasts.
Two things were making it take exactly as long as listening would. The `n` on a
direct YouTube link was left as it came: an untouched one is not refused on a
media URL the way it is on the streaming endpoint — it is served, at roughly
the speed the track plays at. It is descrambled now, the same as the SABR
endpoint's always was, which also gives playback the headroom it never had. And
the fetch asks for a byte range instead of opening a plain GET, because a
request that names a range is a download to the far end rather than a stream,
and is answered at the speed of the line. A server that will not be asked that
way is fetched the old way instead.

- The whole `admin` group runs from the console now, not just `doctor` and
  `download`: `/cdisc reload` works from a startup script, a scheduler or a
  remote panel without anybody having to log in first. `ytsetup` is the one
  exception — it ends in a clickable link and then waits for the person who
  asked to come back with a code, and the console can do neither. Everything
  outside `admin` still needs a player, since it needs a disc in a hand or a
  menu on a screen; asked from the console, those now say so and point at what
  does work, instead of the bare "Only players can run this." Tab completion
  offers the console only what it can actually run.

- A disc pushed into a jukebox by a hopper no longer plays twice — the custom
  track and the vanilla record sound at the same time, over each other.
  Suppressing the vanilla sound depends on the jukebox being known as ours
  before vanilla announces the disc, and the only thing that marked it was a
  player's right-click; a hopper right-clicks nothing, so the announcement had
  nothing to be cancelled by. Marked on the item transfer now, which covers
  hoppers, droppers and hopper minecarts. The mark is checked again the tick
  after, so a transfer that doesn't land can't leave a jukebox that silently
  swallows the next ordinary record.

### 📎 Files from Discord are a source of their own

A link to a file attached to a Discord message plays now without switching
`http` on, and the disc it makes says what it is: **Discord** as the artist,
the file's own name as the track. Before this it was either refused — `http`
is off by default, and it has to be, since it opens every URL on the internet
at once — or, on a server that had switched it on, written to a disc reading
"Unknown artist — Unknown title", because an upload carries no tags and
LavaPlayer fills both fields with a stand-in that looks like real metadata.

Pasting a link out of Discord is the one thing players actually do with a
direct file, so it is `discord: true` in `sources.yml` by itself: one host,
not the whole internet. A file that *does* carry tags keeps them — the naming
only steps in where there was nothing.

**The name comes from Discord rather than from the link**, because the link
hasn't got it. Discord reduces a filename to ASCII on the way into the URL:
`так похуй [tRAmPLYjbGg].mp3` is served from `.../tRAmPLYjbGg.mp3`, with the
Russian words and the brackets gone, and the whole name handed back in the
`Content-Disposition` header instead. So anything not written in English
exists only in that header, and reading the URL — which is what this did at
first — gets an English-alphabet stub of the name and nothing else. It is one
request, made once, when the disc is written rather than when it plays; if it
fails or the link has expired, the name out of the URL is still written.

One thing worth knowing, and it is Discord's rather than ours: those links
expire about a day after they are copied. The `ex=`/`is=`/`hm=` on the end is
a signature with a deadline in it, and Discord answers an expired one with a
404 — which used to arrive in game as "track not found" and read as though
the file had been deleted. A link past its date now says so, and says where
to get a fresh one. `/cdisc download` is how to keep one for good: the file
is fetched once and plays off the disk afterwards.

- Repeat and shuffle now survive a restart. They were held in memory only, so
  `resume-playback` brought the track back and left the jukebox on defaults —
  it finished the resumed disc and stopped. They are written down beside the
  position now, and restored before it, so a jukebox that was left looping is
  still looping. Saved for stopped jukeboxes too: how one is set is not about
  the track that happened to be in it, and losing that on every restart was the
  same bug wearing a different hat.

- Age-restricted tracks now go to the backend first, whether or not it is
  switched on in `sources.yml`. YouTube refuses them for wanting an account,
  which no amount of retrying from the server can supply, so the paths that
  would be refused now run after the one that has one rather than before it.
  The refusal is recognised on both roads — the direct SABR attempt and
  youtube-source — so a server with either or neither switched on still finds
  it.

### 🪟 The player screen is a real window now

1.21.6 gave servers dialogs — actual windows, with a title, text, buttons and controls. On 1.21.6 and up the player screen opens as one instead of as a chest full of items pretending to be buttons. `player-dialog: false` in `config.yml` puts the inventory screen back.

What that buys is that the screen can be shaped like what it does. Most of what an inventory has to make a button is not a verb at all: a volume is a number, repeat is one of three things, whether the words show is yes or no. Each of those was an item you clicked, with its own state written into its name and "left click louder, right click quieter" in the lore.

- **Sliders** for both volumes and for the position in the track.
- **A choice button** for repeat, and **tick boxes** for shuffle, the words above the jukebox, and track messages.
- **A field** for typing a timecode, for when a slider is not precise enough.
- **Square buttons** for what is left, which is only what actually does something — one glyph each, with the sentence in the tooltip where it belongs.

Nothing applies until Apply is pressed, and only what actually moved is written: coming here to change the volume does not also seek the track back to where it stood when the screen opened.

**No words of the song on this screen.** They belong over the jukebox, where a hologram can be as tall as it likes and everybody standing there reads them. Here they could only go above the controls — a dialog draws title, then body, then inputs, then buttons, in that order and no other — so every line of song pushed every button further down. The track name went into the title bar for the same reason.

**Controls that would do nothing are left out rather than greyed out**: no position slider on a live stream, no beacon range without a beacon under the jukebox, no speaker settings on a jukebox whose group screen owns them.

There is one cost and it is forced rather than chosen. A dialog reports its inputs only when a button is pressed, so a screen carrying them cannot be rebuilt underneath the player — every redraw would drag every slider home. So the position it shows is where the track was when the screen was drawn, and any press draws it again.

**Only this screen.** The disc queue and the speaker settings stay inventories, because both are about picking discs up and putting them down and a dialog has nowhere for an item to go. A button that leads to either closes the dialog and opens the chest.

Ignored entirely below 1.21.6 and on Spigot, which has no dialog API — those servers get the inventory screen whatever the setting says. Both screens drive the same set of actions underneath, so neither can drift away from the other.

### 💬 Switching the lyrics on says what came of it

Turning the hologram on said only that it was on. Then, quite often, nothing appeared over the jukebox — and there was no way to tell which of several reasons applied, because they all look the same from where the player is standing, which is empty air.

There is now a line in chat for each one: the words were found and the hologram is live; this track has no synced lyrics; nothing is playing yet; a live stream has no timed lyrics at all; or the jukebox is in your hand, where the words go to the screen instead of the sky.

The answer usually isn't known at the moment of the click — the first lookup for a song goes out to the lyrics databases — so it says it is looking and comes back with the outcome. The wait is dropped the moment it stops being about anything: the hologram switched off again, the queue moved on to another track, or the player left. If the databases never answer, it says that too, rather than leaving a "looking..." that never resolves.

### ⏱ The length limit applies before the choice, not after it

`limits.max-track-seconds` in `permissions.yml` was only checked at the moment a disc was written. A search offered every hit it found, including the hour-long ones, and the refusal arrived after the player had already picked one — which reads as the setting not working at all.

Results now come pre-filtered: a search shows only what this player may actually write, and the count is taken after filtering, so they still get a full page rather than the leftovers of one. When a search finds things but none of them qualify, it says so, instead of claiming nothing was found and sending the player off retyping their query.

**The server's own music folder is exempt from the limits now, where it used to be caught by them.** These rules are about what players may pull in off the internet. A file under `local:` was put in that folder by the admin deliberately and is already offered to everyone who can type the prefix, so its length is not a player's decision to be limited.

**The playlist screen was skipping the limit entirely.** `/cdisc disc playlist` handed out a slot per track and wrote whatever discs were dropped in, however long the track — the whole rule was one command away from being bypassed. It now offers only the tracks that qualify and says how many it left out.

Nothing changes for staff, who were never subject to these limits, or for servers leaving `max-track-seconds` at 0.

### 🔈 Speaker groups play as one, not as an echo

**A group's jukeboxes now leave the server on a single clock.** Standing between two of them no longer sounds like a slapback — the frame a listener gets from one speaker is the frame they get from the others, sent in the same instant from the same thread.

Plasmo Voice gives every audio source a sender of its own, a loop that waits twenty milliseconds and never quite makes up the remainder it lost. Two sources started a moment apart therefore never agree again, and the gap only widens. Capping how much each one could bank held the damage to about sixty milliseconds — audible as a doubled sound, and bought by dropping frames to stay there. Members of a group skip that machinery entirely now: their audio goes on the wire from the thread that decoded it, so there is no second clock left to drift.

The deep buffer stays where it belongs, on a jukebox playing alone, where a decoder that pauses for a moment should not leave a hole and no one can hear the delay that hides it. Simple Voice Chat was never affected — it always sent from the calling thread.

### 🧹 The console speaks English, and says less

Every message CDisc writes to the server console was in Russian, which is no use to most of the people who install it. They are all in English now.

There are fewer of them, too. Two fired on every single track and told nobody anything — one on each non-YouTube load, one on each YouTube load that worked — so a quiet track is now quiet. What is left is what somebody would act on: a failure, a fallback being taken, or a setting that needs turning on.

Player-facing text is unaffected: it comes from `languages/`, and `/cdisc player` still accepts `вкл` and `выкл` alongside `on` and `off`.

### 🔊 Speaker volume goes both ways

Left click is louder, right click is quieter. It used to be one button that only stepped up and wrapped past full round to silence, which meant nudging the volume up one notch too far switched the speaker off — and getting back to where you were took nine more clicks.

The jukebox's own volume already worked this way; the speaker settings screen now agrees with it.

### 🎵 Spotify links play, and keep their own names

**A Spotify link is matched to the YouTube video of the same recording, by ISRC.** That code names a recording rather than a song, so the remaster, the cover, the karaoke version and the sped-up edit are all told apart — which searching by title cannot do. YouTube returns the label's own upload for it, and its length agrees with Spotify's to within a second.

No mirroring source manager is involved any more. The link resolves here and then goes down the same direct road as everything else, which is also why it now plays at all: the old path handed it to youtube-source, and that no longer works.

- **Two ways to resolve one, either is enough.** Spotify credentials in `tokens.yml` — one request per track, the token behind it cached — or a token server that resolves Spotify links, which does the whole thing in one. With neither, the console says exactly that instead of calling it a broken link.
- **A match has to look like the track, not merely last as long as it.** YouTube does not answer "nothing found": a query it has no answer for is padded with whatever it has, and one such video matched a track's length to within half a second and went onto a disc as a news report. Length is now the tie-breaker it always should have been, behind the artist or the title actually agreeing.
- **The disc keeps Spotify's title and artist.** An Art Track lives on an "Artist - Topic" channel, and that suffix was ending up on discs.
- **Making a disc takes the same road as playing one.** It used to go straight to the libraries, which is why a Spotify link could play but not be written to a disc.
- **The backend URL is honoured wherever it was written** — `sources.yml` is its home, `tokens.yml` is where people put it anyway, and a bare host now finds either service's endpoint instead of guessing one and giving up on a 404.

### 🎧 YouTube plays without any of the bot-check machinery

**A client YouTube still answers the old way.** Asked as visionOS, YouTube returns every format with a direct link: no SABR endpoint, no proof of origin, no BotGuard, no `n` to descramble, and nothing attested. The one thing it wants is a visitor id, and any will do — one ordinary page fetch hands one out.

- **SABR stays behind it**, for what visionOS is not given — videos marked as made for kids, among others.
- **Tokens become optional.** `po-token` and `visitor-data` matter only on the SABR path now. Configure nothing and YouTube still plays.
- **The console names which path a track came out of**, because the two break differently: a direct link can expire or be refused from another address, while SABR can lose its attestation and answer with a minute of audio.
- **YouTube is asked first now, rather than after youtube-source has failed.** The library is not even started unless the direct road comes back empty, which is why the console no longer carries a page of `All clients failed` before every track that then played fine.

### 🔑 YouTube tokens keep themselves current

**One pair, kept current by itself.** What YouTube attests is the visitor identity — eleven characters — and not the long string that carries it. The string can be re-signed with one ordinary web request, so CDisc does that about once an hour, with no browser and no service in the middle, and writes the result back into `tokens.yml`. The values there change on their own now; that is the feature.

`backend-youtube-tokens.json` beside it records when that last happened, so a restart installs what is already there instead of fetching anything.

- **The first pair has to come from somewhere.** Paste one into `tokens.yml`, or point `youtube.po-token-backend.url` at a token server. Nothing is shipped as a default: an identity baked into a release is one every install shares, and a shared identity wears out — two died inside a day while this was being built. With neither, the console says so at startup instead of half working.
- **`visitor-data` without `po-token` is now a supported arrangement**, and the ordinary one. On every measurement made here the identity is what decides whether a track plays whole or stops a minute in; the token decides nothing about playback.
- **A pair dropped into the file from outside is picked up within half a minute** — by the token server, a cron job, or somebody with an editor — without a restart.

### 📡 YouTube's new streaming protocol is read directly

**SABR streams play.** YouTube has begun answering some requests with a response that lists every format and gives a direct link to none of them — the audio is meant to be pulled through a single endpoint by POSTing to it repeatedly, saying where playback has reached. There is no file and no byte range, so every downloader built around a URL comes away empty.

That endpoint is now read here. Turned on by `youtube.sabr` in `sources.yml`, on by default, and only ever used for a response that has no links in it — a server YouTube still serves the old way never touches this path.

It needs a visitor identity: SABR checks one on every request, and without an attested one a track plays about a minute and stops. CDisc keeps one current by itself once it has one — see above. The request is made signed-out, which is what the identity is for — an OAuth login alongside an identity earned in a private window tells YouTube two different stories about who is asking.

Seeking works. SABR has no byte offsets, but the same request returns the same bytes, so a rewind is either replayed out of the opening kept in memory or the conversation begun again from the start.

### 🐛 Fixed

**A YouTube track that resolved and then went silent now says why, and plays anyway.**

YouTube has started answering some requests with SABR: the response is complete — every format listed, audio included — but none of them carries a direct URL, because everything is meant to be pulled through a single streaming endpoint instead. youtube-source skips a format it has no URL for, skips all of them, and reports `No supported audio streams available, available types:` with nothing after the colon.

That message reads as *"YouTube sent us nothing"* and means the opposite, which is a long way to walk in the wrong direction — through tokens, addresses and OAuth, none of which are involved.

- The console now names it: forms, but no direct links, and neither the tokens nor the server's address are at fault.
- A SABR answer puts the Custom API ahead of youtube-source for the next ten minutes, so the tracks after it play at once instead of each one starting, failing and falling back. `/cdisc admin reload` clears the preference, since a reload is somebody having changed the very settings worth retrying.
- With no backend configured, the console says so plainly rather than letting the disc die quietly.

**A jukebox left on pause keeps its disc.**

Pause it, walk off for a minute, and the jukebox came back empty-handed: no track, nothing playing, and the only way on was Play from the top and a seek back to where you left off. Local files did it too, so it was never the network.

While paused, nothing was asking the player for audio — quite reasonably, since there is none to send. But LavaPlayer reads a player nobody has asked in a minute as abandoned and stops its track out from under it. It is asked for a frame every tick now, paused or not; paused, it hands back nothing, which is exactly what pause should sound like, and the disc is still there when you come back.

## 1.8.0

**The lyrics hologram is yours now.** Every jukebox can be styled on its own, every player can carry a look that follows them, and a jukebox in your hands finally has somewhere to show its words.

---

### 🎨 Style the hologram

The hologram used to look one way for the whole server. Now there are three places to set it, each falling back to the next: **your own preset → the jukebox's → the config**.

**Per jukebox** — right-click the lyrics paper in the jukebox GUI.

| Colours page | Layout page |
|---|---|
| Background colour and opacity | Size and height |
| Brightness | Line width |
| Colour, opacity and formatting of the line being sung | Lines above and below |
| The same for the lines around it | Drop shadow, see-through |
| | Fade length, slide, countdown dots |

**Per player** — `/cdisc preset` opens the same screen for a look that follows you to every jukebox on the server.

**A live sample while you edit** floats in front of you and disappears with the screen. Changes land a tick after the click instead of waiting for the next line to be sung.

### 🤝 Share a look

```
/cdisc preset share <player>
```

They get **[Accept]** and **[Decline]** buttons in chat, and nothing about what they read changes until they press one. What they accept is what you showed them, not whatever you moved on to afterwards.

### 📜 Lyrics for a jukebox you're carrying

A hologram needs a jukebox standing still, so a carried one had nothing to read.

```
/cdisc player scoreboard
```

The words go in **your sidebar** while you carry it, and **above your head** for everyone else. Off unless you ask for it, and the sidebar slot is handed straight back when you put the jukebox down.

### 🔊 Two volumes

A jukebox has one volume for everyone standing around it — and, while carried, a second one for whoever is holding it.

- Set the jukebox to 30 and the carrier gets 30 too, until they say otherwise
- **Shift-click** puts them back on the jukebox's
- Both live in the player screen as light blocks that carry the level as their own brightness, so you can read it off the slot

### 🔀 Shuffle

A new button in the player screen. Picks the next disc at random, refusing only the one that just played.

### ▶️ The player opens on a stopped jukebox

It used to refuse unless something was playing — which is backwards at the moment you want it: the track ended and you want to start another.

- Opens on a jukebox that's playing, has a queue, still holds a disc, or is in your hands
- The transport **starts** something instead of sitting greyed out
- **Right-click the air** while carrying a jukebox to open it

### 🌍 One language for the whole server

```yaml
language: auto   # or ru_RU, en_US
```

`auto` keeps the old behaviour of following each player's client. Set a language and the server sounds like one place — console and links included, not just chat.

Translating it yourself? Drop `languages/<name>.yml` into the plugin folder and name it here. Missing keys fall back to English, so a half-finished file still works.

### ⚙️ Commands are seven groups instead of twelve

```
create · disc · preset · player · pair · messages · admin
```

`clear`, `convert` and `create-playlist` live under `disc`; `reload`, `doctor`, `download` and `ytsetup` under `admin`. **Every old spelling still works** — they're just no longer suggested, so the tab-complete list is readable.

### ✨ Smaller things

- `/cdisc player info` tells you what's playing next
- `/cdisc doctor` reports the lyrics databases, songs cached, holograms floating and players with a preset
- The progress bar is white. Red still means a live stream

### 🐛 Fixed

- **The remote cipher solver was off** on any server whose `sources.yml` predated the setting — stream URLs went unsolved and it looked switched off on purpose
- **A carried jukebox lost its speaker settings**, so the volume button did nothing in exactly the mode where it matters most
- **Putting a carried jukebox down started the vanilla record**, and taking the disc back out couldn't stop it
- The queue screen's exit button closed the window instead of going back to the player

### 📖 Documentation

The [po-token guide](https://wiki.2281273.xyz/#/youtube) now tells you to press **Show more** in DevTools — the payload is cut short above the token, and the copied text wouldn't parse. Its extractor also refuses a placeholder token instead of handing you one YouTube treats as no token at all.
