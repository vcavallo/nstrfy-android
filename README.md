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

Download the latest APK from [Releases](https://github.com/vcavallo/nstrfy-android/releases) or install via [zapstore](https://zapstore.dev).

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

Based on [ntfy-android](https://github.com/binwiederhier/ntfy-android) by [Philipp C. Heckel](https://heckel.io), distributed under the [Apache License 2.0](LICENSE).
