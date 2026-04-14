# nstrfy Android - Development Notes

## Build

### Debug build (for testing on local device via HTTP server)
```bash
nix develop /home/vcavallo/src/NarChives --command bash -c "./gradlew clean assembleDebug"
```
APK output: `app/build/outputs/apk/debug/app-debug.apk` → serve from `http://10.0.1.181:8222/app-debug.apk`

### Release build (for GitHub releases + zapstore)
```bash
nix develop /home/vcavallo/src/NarChives --command bash -c "./gradlew clean assembleRelease"
cp app/build/outputs/apk/release/app-release.apk nstrfy-<VERSION>.apk
```

## Versioning Discipline

**Debug builds between releases: do NOT bump versions.** Iterate freely; the user tests them on their device via the local HTTP server. No commit, tag, or version change needed for debug-only changes.

**When preparing a release:**
1. Bump `versionCode` (monotonically increasing int) and `versionName` (semver) in `app/build.gradle`
2. Update `VERSION` and `CHANGELOG.md`
3. Commit the version bump
4. Tag: `git tag v<VERSION>`
5. Build release APK: `./gradlew clean assembleRelease`
6. Copy/rename APK: `nstrfy-<VERSION>.apk`
7. Push branch + tag to fork
8. Create GitHub release, attach APK
9. Publish to zapstore: `zsp publish`

**Rule of thumb:** bump versions sparingly. Ship multiple debug iterations between releases, then bundle them into one release version bump.

## Schema Migrations & Safe Updates

When making a change, before building a new APK, consider: **does this change the DB schema?**

- **No schema change** → safe update, users install over the top. Most UI/service changes.
- **Schema change** → requires a new migration. Never modify migration 19 (frozen at v1.0.0). Add `MIGRATION_19_20`, bump `version` in `Database.kt`, regenerate schema export with `./gradlew kspDebugKotlin`.

Tell the user whether the new APK is "safe to update over the existing install" or "requires uninstall/reinstall" (only if schema changes happened pre-v1.0.0 would need the latter — shouldn't happen post-release).

## Room Entity Checklist

When adding a new column to `Subscription` (or any Room entity with manual POJO mapping):

1. Add `@ColumnInfo` field to `Subscription` entity (primary constructor)
2. Add to the secondary constructor + delegated `this(...)` call
3. Add to `SubscriptionWithMetadata` data class
4. Add `s.newField` to ALL 5 `@Query` SELECT statements in `SubscriptionDao`
5. Add `newField = s.newField` to **BOTH** `toSubscriptionList()` AND `toSubscription()` in `Repository.kt`
6. Add `ALTER TABLE` in migration (new one, not 19)
7. Update schema export (`app/schemas/`) — run `./gradlew kspDebugKotlin`

Missing step 5 silently uses the Kotlin default value — the DB will have the correct value but the app ignores it. This is extremely hard to debug.

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

**Migration defaults vs Kotlin defaults:** the SQL `DEFAULT` value in migrations sets what existing rows get on upgrade. The Kotlin default sets what new rows get when inserted from code. Make sure these match OR you intentionally know why they differ. Example bug we hit: migration had `DEFAULT 1` for `whitelistEnabled` but Kotlin default was `false`, so subscriptions created pre-migration-change got `true` and silently rejected all senders.

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
