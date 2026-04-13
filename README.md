# nstrfy - Nostr Push Notifications for Android

nstrfy is a native Android app that receives push notifications over [nostr](https://nostr.com). It listens for [kind 30078](https://github.com/nostr-protocol/nips) events on configurable relays and displays them as native Android notifications with full priority, muting, and action support.

Think [ntfy](https://ntfy.sh), but decentralized -- no server required, just nostr relays.

**Work in progress! base case works, but important additional features are being added and bugs being fixed**

## How it works

1. Generate or import a nostr keypair in the app
2. Add one or more relays (e.g. `wss://nos.lol`)
3. Subscribe to a topic (e.g. `alerts`, `deploys`, `home`)
4. Send notifications from any machine using [nstrfy.sh](https://github.com/vcavallo/nstrfy.sh):

```bash
nstrfy.sh send \
  --to npub1abc...def \
  --title "Deploy complete" \
  --message "v2.4.1 is live on production" \
  --priority high \
  --topic deploys \
  --relays wss://nos.lol
```

The app receives the event, decrypts the NIP-44 encrypted payload, matches the topic, and shows a native Android notification.

## Protocol

nstrfy uses nostr kind 30078 events following the [nstrfy protocol](https://github.com/vcavallo/nstrfy.sh/blob/main/NIP-DRAFT.md):

- **Encryption**: NIP-44 (primary), NIP-04 (fallback), or plaintext for public topics
- **Routing**: `#p` tag for inbox delivery to a specific pubkey; topic matching is done client-side from the decrypted payload
- **Payload**: JSON with `title`, `message`, `priority`, `topic`, `tags`, `click`, `icon`, `actions`
- **Priority**: `urgent`/`max`, `high`, `default`, `low`, `min` -- mapped to Android notification channels
- **Spam control**: Per-topic sender allowlists (npub whitelist). Nostr's namespace is global, so client-side filtering is the only protection.

## Features

- **Persistent relay connections** via foreground service with automatic reconnection
- **Topic-based subscriptions** with per-topic sender allowlists
- **Inbox mode** (events tagged to your pubkey) and **public mode** (events from allowlisted authors)
- **Full notification support**: priority levels, muting, auto-delete, actions, icons
- **Secure key storage** via Android Keystore (EncryptedSharedPreferences)
- **Import/export keys** (nsec bech32 or hex)
- **Multiple relay support** with per-relay enable/disable

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
| ntfy server API | nostr relays (kind 30078) |
| HTTP auth (user/password) | nostr keypair (nsec/npub) |
| Server-side topic routing | Client-side topic matching after decryption |
| Firebase Cloud Messaging | Persistent WebSocket to relays |

Key dependencies:
- [Quartz](https://github.com/nicegamer7/quartz-android) (`com.vitorpamplona.quartz:quartz-android`) -- nostr protocol library from Amethyst
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) -- JSON parsing for notification payloads
- [AndroidX Security](https://developer.android.com/jetpack/androidx/releases/security) -- encrypted key storage

## License

Based on [ntfy-android](https://github.com/binwiederhier/ntfy-android) by [Philipp C. Heckel](https://heckel.io), distributed under the [Apache License 2.0](LICENSE).
