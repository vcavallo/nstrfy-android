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

## Key Architecture

- `NostrConnection` — single connection to nostr relays, replaces ntfy's WsConnection/JsonConnection
- `NostrNotificationParser` — decrypts NIP-44/NIP-04 events and maps to ntfy Notification objects
- `KeyManager` — stores nsec in Android Keystore via EncryptedSharedPreferences
- `SubscriberService` — creates one `NostrConnection` for all subscriptions (not per-URL like ntfy)
- WoT search uses `wss://brainstorm.world/relay` with NIP-50 for finding nostr profiles by name
