# nstrfy Android - Development Notes

## Build

```bash
nix develop /home/vcavallo/src/NarChives --command bash -c "./gradlew assembleDebug"
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Room Entity Checklist

When adding a new column to `Subscription` (or any Room entity with manual POJO mapping):

1. Add `@ColumnInfo` field to `Subscription` entity (primary constructor)
2. Add to the secondary constructor + delegated `this(...)` call
3. Add to `SubscriptionWithMetadata` data class
4. Add `s.newField` to ALL 5 `@Query` SELECT statements in `SubscriptionDao`
5. Add `newField = s.newField` to **BOTH** `toSubscriptionList()` AND `toSubscription()` in `Repository.kt`
6. Add `ALTER TABLE` in migration
7. Update schema export (`app/schemas/`)

Missing step 5 silently uses the Kotlin default value — the DB will have the correct value but the app ignores it. This is extremely hard to debug.

## Migrations

- **v1.0.0 schema is version 19** — frozen, never modify `MIGRATION_18_19` again
- Any new column or table = new migration (20, 21, etc.)
- Always run `./gradlew kspDebugKotlin` after schema changes to regenerate the export

## Event Kind

- nstrfy uses **kind 7741** (regular event, stored by relays)
- Events include NIP-40 `expiration` tag (default 1 hour)
- The kind constant is in `NostrConnection.kt`: `KIND_NSTRFY = 7741`

## Key Architecture

- `NostrConnection` — single connection to nostr relays, replaces ntfy's WsConnection/JsonConnection
- `NostrNotificationParser` — decrypts events via `EventDecryptor` interface and maps to ntfy Notification objects
- `EventDecryptor` — interface with two implementations:
  - `LocalKeyDecryptor` — uses nsec stored in `KeyManager` (EncryptedSharedPreferences)
  - `AmberDecryptor` — delegates to Amber via NIP-55 ContentProvider
- `KeyManager` — dual-mode identity: `INTERNAL` (nsec) or `AMBER` (pubkey + package name only)
- `SubscriberService` — creates one `NostrConnection` for all subscriptions (not per-URL like ntfy)
- WoT search uses `wss://brainstorm.world/relay` with NIP-50 for finding nostr profiles by name

## Amber Integration Notes

- ContentProvider works silently with "Always allow" — no UI shown
- With "Ask" mode, ContentProvider may show prompts even from background — for `#p`-tagged events, skip ContentProvider in background and queue for foreground Intent decryption
- Always dedup events by ID (inbox + public filters both match encrypted events)
- Don't permanently disable ContentProvider after one failure — breaks "Always allow" users
- Pending encrypted events stored in `Application.pendingEncryptedEvents` (in-memory queue)
- Both `MainActivity` and `DetailActivity` handle foreground Amber decrypt via `amberDecryptLauncher`
