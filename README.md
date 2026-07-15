# Kick Chat Side Panel (RuneLite plugin)

Shows Kick chat in a RuneLite side panel - Party-Hub style - sibling to the
[Twitch Chat Side Panel](https://github.com/georgeparamore/runelite-twitch-chat-side-panel)
plugin, but for [Kick](https://kick.com) instead of Twitch.

It only ever connects to the single channel configured in the plugin's settings - there's
no free-text field or other way to switch to a different channel from the panel itself.

Reading chat works anonymously with no login at all. Logging in with your Kick account
additionally lets you send messages from the panel - click **Log in with Kick**.

## Using it

1. Open the plugin's settings (gear icon in the plugin list) and set **Kick channel** to
   whichever channel you want chat for - either the plain slug, or you can paste the
   channel's full kick.com URL straight from a browser and it's cleaned up automatically.
2. Open the plugin's side panel (toolbar icon) and click **Connect** to start reading chat -
   no login needed for this part. The feed shows a "Connected to channel" notice so it's
   always clear which channel you're actually looking at. The small external-link icon next
   to the channel name opens that channel's Kick page in your browser.
3. To also send messages, click **Log in with Kick** in the panel - it opens your system
   browser to Kick's login/consent page. Once approved, a message box appears at the bottom
   of the panel.

If no channel is configured, the panel shows "No channel set" and the Connect button is
disabled until you set one.

Config options (gear icon in the plugin list):

- **Kick channel** - the only channel this plugin will ever connect to. Accepts a plain
  channel slug or a pasted kick.com URL.
- **Auto-connect on login** - connects automatically when the client starts.
- **Color usernames** - use each chatter's Kick name color.
- **Show timestamps** - show `HH:mm` per message.
- **Message history** - how many messages to keep before older ones scroll off.

## Before you build/run this

Unlike the Twitch plugin, Kick requires a `client_secret` for its OAuth token exchange even
in the Authorization Code + PKCE flow this plugin uses for login (confirmed against
[Kick's dev docs](https://github.com/KickEngineering/KickDevDocs) - Twitch's flow needs no
secret at all, which is why the Twitch plugin's Client ID can just be hardcoded safely).
`KickSidePanelPlugin.CLIENT_ID` / `CLIENT_SECRET` are placeholders - before this plugin can
log anyone in, you need to:

1. Register an app at Kick's developer portal (linked from
   [docs.kick.com](https://docs.kick.com)).
2. Set its redirect URI to exactly `http://127.0.0.1:17953/callback` (this plugin runs a
   short-lived local HTTP listener on that fixed port to catch the OAuth redirect - see "Login
   / sending" below for why).
3. Request the `chat:write` scope.
4. Replace the two placeholder constants in `KickSidePanelPlugin` with your app's real
   Client ID and Secret.

Baking a `client_secret` into an open-source plugin's public source means it isn't actually
secret - anyone can read it out of the repo or the built jar. This is a real tradeoff, not
an oversight: Kick's API has no "public client" type the way Twitch's does, so there's no
way to do PKCE-only login without embedding *something* confidential. The actual security
boundary is still each user's own Kick login/consent screen, same as it would be for any
other app using this pattern - but it does mean this Client Secret should be treated as
"identifies the app," not as something that protects anything by itself, and that Kick
revoking/rotating it (e.g. after abuse) would break login for every user until the plugin is
updated with a new one.

Reading chat needs no credentials at all and works as soon as you set a channel and click
Connect - only the optional login/send feature depends on the above.

## How it works

**Chat feed - reading**: Kick's *official* public API (docs.kick.com) has no way to read
live chat. Its only incoming-message mechanism is a webhook event (`chat.message.sent`) that
Kick POSTs to a public HTTPS URL you host - not something a desktop plugin can receive
without standing up a relay server. So instead, same as every other open-source Kick chat
client, this plugin reads chat over Kick's *unofficial* Pusher WebSocket feed - the same one
Kick's own web client uses (`wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679`, subscribing
to a `chatrooms.<id>.v2` channel). See `KickChatClient`, built on
`java.net.http.HttpClient`'s WebSocket API (JDK 11+, no extra dependency).

**This is a real reliability risk, not a hedge**: this feed is undocumented and Kick could
change the Pusher app key, channel naming, or message shape at any time with no notice, which
would break chat reading until the plugin is updated. Every third-party Kick chat integration
that exists today accepts this same tradeoff, because there currently isn't another way to
get a live chat feed into a client Kick doesn't operate itself.

**Channel resolution**: two different Kick-side IDs are needed, resolved two different ways.
Sending a message needs a `broadcaster_user_id`, resolved via the official API
(`GET /public/v1/channels?slug=...`, `KickApiClient.resolveBroadcasterUserId`) using an app
access token (Client Credentials grant - no user login required for this lookup). Reading
chat needs a numeric chatroom id, which the official API does not expose anywhere - the only
way to get it is Kick's unofficial `kick.com/api/v2/channels/{slug}` endpoint
(`KickApiClient.resolveChatroomId`), which has been reported to sit behind bot-protection for
non-browser clients. This plugin sends a normal browser User-Agent on that one call to reduce
the odds of being blocked; if it still fails, the panel shows "Couldn't find that Kick
channel."

**Login / sending**: uses OAuth 2.1's Authorization Code + PKCE flow (`KickAuthService`) -
Kick has no device code grant, so unlike the Twitch plugin this needs a real redirect. A
short-lived local HTTP server on `http://127.0.0.1:17953/callback` catches it after you
approve the login in your system browser (opened automatically). Sending a message goes
through the official REST API (`POST /public/v1/chat`, `chat:write` scope) - not the Pusher
connection, which is read-only. A message sent this way is expected to arrive back over the
same Pusher feed like anyone else's (Kick broadcasts every chat message to every subscriber
of the room), so unlike the Twitch plugin there's no local-echo logic for your own sent
messages.

**@ mentions**: typing "@" in the message field pops up a filtered list of recently-seen
chatters to complete from (arrow keys / Enter / Tab to pick, Escape to dismiss, or click
one) - see `MentionAutocomplete`. A message that mentions your username (with or without the
"@", case-insensitive) is highlighted. Clicking any sender's name in the feed starts a reply
to them by setting the message field to "@Username ".

## What's deliberately not here

- **No emote rendering.** RuneLite's Plugin Hub rejected exactly this for the Twitch plugin
  (see [Rejected or Rolled Back Features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features)) -
  distributing third-party emote images isn't allowed. Chat is plain text only.
- **No badge icons.** Kick's badge icon API (if any) hasn't been researched yet; badges are
  a possible future addition, not a rejected one.
- **No sub/gift/follow events.** Kick's Pusher feed carries these, but their exact shape
  hasn't been verified against a live channel yet - left for a future update rather than
  shipped unverified.
