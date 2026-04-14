# nstrfy - Nostr Push Notifications for Android

nstrfy is a native Android app that receives push notifications over [nostr](https://nostr.com). It listens for **kind 7741** events on configurable relays and displays them as native Android notifications with full priority, muting, and action support.

Think [ntfy](https://ntfy.sh), but decentralized -- no server required, just nostr relays.

## How it works

1. Connect your nostr identity via [Amber](https://github.com/greenart7c3/Amber) (recommended) or import an nsec
2. Your relay list is automatically imported from your nostr profile (NIP-65)
3. Subscribe to a topic (e.g. `alerts`, `deploys`, `home`)
4. Send notifications from any machine using [nstrfy.sh](https://github.com/vcavallo/nstrfy.sh):

```bash
# Public notification (plaintext, no encryption)
nstrfy.sh send \
  --title "Deploy complete" \
  --message "v2.4.1 is live" \
  --topic deploys \
  --relays wss://nos.lol

# Encrypted notification (NIP-44, to a specific npub)
nstrfy.sh send \
  --to npub1abc...def \
  --title "Secret alert" \
  --message "For your eyes only" \
  --topic alerts \
  --relays wss://nos.lol
```

The app receives the event, decrypts the NIP-44 encrypted payload (via Amber or local key), matches the topic, and shows a native Android notification.

## Protocol

nstrfy uses nostr **kind 7741** events with NIP-40 expiration:

- **Event kind**: 7741 (regular, stored by relays)
- **Encryption**: NIP-44 encrypted to recipient's pubkey, or plaintext for public topics
- **Routing**: `#p` tag for inbox delivery to a specific pubkey; topic matching is done client-side from the decrypted payload
- **Expiration**: NIP-40 `expiration` tag (default 1 hour) -- relays auto-delete old notifications
- **Payload**: JSON with `title`, `message`, `priority`, `topic`, `tags`, `click`, `icon`, `actions`
- **Priority**: `urgent`/`max`, `high`, `default`, `low`, `min` -- mapped to Android notification channels
- **Spam control**: Per-topic sender allowlists (npub whitelist)

## Features

- **Amber signer integration** (NIP-55) -- private keys never touch nstrfy; decryption delegated to Amber
- **Automatic relay import** from your nostr profile (NIP-65 kind 10002)
- **Persistent relay connections** via foreground service with automatic reconnection
- **Topic-based subscriptions** with per-topic sender allowlists
- **Public and encrypted notifications** with visual lock/unlock indicator
- **WoT-powered user search** via [brainstorm.world](https://brainstorm.world) for finding npubs by name
- **Full notification support**: priority levels, muting, auto-delete, actions, icons
- **Hybrid Amber decryption**: silent background decryption when "Always allow" is set; foreground approval fallback otherwise
- **Multiple relay support** with per-relay enable/disable

## Installation

Download the latest APK from [Releases](https://github.com/vcavallo/nstrfy-android/releases) or install via [zapstore](https://zapstore.dev/apps/io.nstrfy.android).

## App setup and common gotchas

1. Install the app, open it
2. You'll see a banner about battery/background activity - select "Fix" and allow background activity
3. You'll see another banner about connecting your nostr identity, tap "Open Settings"
4. Login with Amber (or paste your nsec - not recommended) and wait for your relays to be set up
5. Go back to the topics list and add a topic with the "+" button in the lower right
6. Choose any topic name. 7741 events are tagged with particular topic names, and this is where you determine which "topics" you want to subscribe to
6a. The whitelist checkbox will control whether you see ALL notifications sent to this topic, or only ones from a set of npubs you specify (this can be changed from the subscription settings page once you've subscribed to the topic)
7. Publish a 7741 event to this topic to get a push notification

### Whitelist/Encryption

If you have the whitelist enabled, you won't see any notifications unless the author is on the whitelist for this topic.  
In testing and touring the app, you might want to **subscribe to a random topic name and leave the whitelist off**. This will make it easier to push notifications to the topic to get a feel for things.  

7741 events can be encrypted for a particular recipient. The whitelist still applies, however (encrypting an event to someone doesn't ensure they see it - their whitelist may be blocking you still).

### Common issues

- _You didn't allow background / unrestricted battery._ This will cause notifications to be quietly missed.
- _You didn't approve Amber_. Notifications encrypted to you require Amber to decrypt. **It is highly recommended to set amber to "Allow" and "Always"** so that push notifications can be decrypted in the background.  
- _The push notification says "you have an encrypted notification"_. If you have Amber set to "Ask" every time, you'll know that you got _some_ notification on _some_ topic, but until you open nstrfy and approve Amber, you won't be able to decrypt the message or know which topic it is in.
- _I don't know how to send a notification!_ Use <https://github.com/vcavallo/nstrfy.sh> for now, or wait a little until we add authoring to this app.
- _I don't know what to do with this!_ Send yourself encrypted push notifications about your daily backups, or reminders to drink water every 4 minutes. Come up with a newsletter stuffed with alpha and sell access to it (encrypt 1 notification per paid recipient). Organize a protest and send live tactics updates to the participants. Petition your favorite blog author to integrate nstrfy updates along with their RSS feed updates. Come up with your own ideas and post about them on nostr.

## Building

Requires JDK 17 and Android SDK. If you use Nix:

```bash
nix develop /path/to/NarChives --command bash -c "./gradlew assembleDebug"
```

Or with a standard Android development setup:

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Release builds

Use `build-release.sh` to build a versioned, signed (debug-key) release APK. The script reads `versionName` from `app/build.gradle` and renames the output accordingly.

```bash
# Bump versionCode + versionName in app/build.gradle first, then:
./build-release.sh           # Build + rename to nstrfy-<VERSION>.apk
./build-release.sh --serve   # Also (re)start a local HTTP server on :8222 for sideloading
```

The script prints the SHA-256 of the resulting APK — handy for zapstore verification.

### Release ceremony

1. Bump `versionCode` (monotonic int) and `versionName` (semver) in `app/build.gradle`
2. Update `VERSION` and `CHANGELOG.md`
3. Commit the version bump
4. Tag: `git tag v<VERSION>`
5. Build: `./build-release.sh`
6. Push branch + tag: `git push fork <branch> && git push fork v<VERSION>`
7. Create GitHub release on the tag, attach `nstrfy-<VERSION>.apk`
8. Publish to zapstore: `zsp publish`

## Architecture

nstrfy is a fork of [ntfy-android](https://github.com/binwiederhier/ntfy-android) with the HTTP transport layer replaced by nostr:

| ntfy | nstrfy |
|------|--------|
| WsConnection / JsonConnection | NostrConnection (Quartz library) |
| ntfy server API | nostr relays (kind 7741) |
| HTTP auth (user/password) | nostr identity (Amber / nsec) |
| Server-side topic routing | Client-side topic matching after decryption |
| Firebase Cloud Messaging | Persistent WebSocket to relays |

Key dependencies:

- [Quartz](https://github.com/vitorpamplona/amethyst) (`com.vitorpamplona.quartz:quartz-android`) -- nostr protocol library from Amethyst (NIP-44, NIP-55, relay client)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) -- JSON parsing for notification payloads
- [AndroidX Security](https://developer.android.com/jetpack/androidx/releases/security) -- encrypted key storage (for nsec mode)

## License

WTFPL – Do What the Fuck You Want to Public License - <https://www.wtfpl.net/>
