# plsDonate — AGENTS.md

## Project Overview

plsDonate is a Paper 1.21.6+ Minecraft Java plugin that integrates with the Indonesian donation platform **tako.id** (`TakoPlatform.java`). It allows players to request donation payment links via in-game chat commands, Java Edition native dialogs (1.21.6+), or Bedrock Edition native forms (Floodgate/Cumulus). All donation flows ultimately converge into executing a `/donate <args>` command, which calls the tako.id API to generate a payment link sent to the donor's email.

## Soft Dependencies & Ecosystem

These plugins are detected at runtime and adapt the donation UI accordingly:

### ViaVersion (`ProtocolVersionUtil.java`)
- Detected in `PlsDonate.onEnable()` via `Bukkit.getPluginManager().getPlugin("ViaVersion")`
- Allows per-player client protocol version detection via `com.viaversion.viaversion.api.Via.getAPI().getPlayerVersion()`
- Used by `ProtocolVersionUtil.supportsDialogs(player)` to determine if a specific Java player's client is 1.21.6+ (protocol >= 771) and thus supports native Java dialogs
- Without ViaVersion, the plugin assumes the player's client matches the server version (1.21.6+)
- **Critical behavior**: When ViaVersion is present and a player connects from a pre-1.21.6 client (protocol < 771), `supportsDialogs()` returns `false`, which forces fallback to Layer 1 (chat-based) flow. Without ViaVersion, `getPlayerProtocolVersion()` returns `771`. When ViaVersion API throws, returns `Integer.MAX_VALUE` (safest assumption).
- Protocol mappings: 1.21.2-1.21.3 = 768, 1.21.4 = 769, 1.21.5 = 770, **1.21.6 = 771**, 1.21.7-1.21.8 = 772

### Floodgate + GeyserMC (`BedrockFormHandler.java`)
- Floodgate detected in `PlsDonate.onEnable()` via `Bukkit.getPluginManager().getPlugin("floodgate")`
- Floodgate allows Bedrock Edition players to join a Java server without owning a Java account
- `isBedrockPlayer(Player)` checks `FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())`
- Uses the **Cumulus** library (bundled with Floodgate) to send native Bedrock forms (`CustomForm`, `SimpleForm`)
- `FloodgateApi.getInstance().sendForm(uuid, form)` dispatches forms to Bedrock clients
- GeyserMC is logged but not directly used by plsDonate — only Floodgate's API matters

### UniDialog (`JavaDialogHandler.java`)
- Shaded library `io.github.projectunified.unidialog.paper.PaperDialogManager`
- Provides Java Edition 1.21.6+ native dialog API (multi-action dialogs)
- `PaperMultiActionDialog` supports text inputs, single-option selectors, body items, header text, action buttons
- `dynamicRunCommand()` allows executing a command with placeholder values from dialog inputs: `/donate $(amount) $(email) $(method) $(message)`
- `runCommand()` for static commands like `/donate <md5hash>`

### SkinsRestorer
- Detected in `DiscordManager` to resolve player skin textures for Discord embed head renders
- Provides `{PLAYER_HEAD_SKIN_RESTORER}` and `{SKIN_TEXTURE_ID_SKIN_RESTORER}` placeholders

### PlaceholderAPI
- Detected in `PlsDonate.onEnable()`, registers `PlsDonateExpansion` for external use

---

## Donation Request Flow — Complete Breakdown

The flow has **2 layers** with Layer 2 having **2 sections** (one per platform). All paths eventually converge into Layer 1's core logic.

### Layer 1: Base Chat-Based Flow (Core)

**Entry point**: `DonateCommand.onCommand()`

#### 1. Command: `/donate <amount> <email> <method> [message]`

**Validation** handled by `DonationValidator` (`DonationValidator.java`):
1. **Amount**: `DonationValidator.parseAmount()` returns `Double` or sends error + null
2. **Config range**: Checked against `donate.amount.min` and `donate.amount.max` from config (only in DonateCommand, not in admin commands)
3. **Email**: `DonationValidator.validateEmail()` — regex + max 64 chars
4. **Method**: `DonationValidator.validateMethod()` — one of `qris`/`gopay`/`paypal`. Per-method minimums: gopay >= 10000, paypal >= 50000, qris = no minimum
5. **Message**: `DonationValidator.buildMessage()` + `validateMessageLength()` — max = `min(config.max, platform.max)`
6. **Cooldown**: Checked via `cooldowns` ConcurrentHashMap. Configurable via `donate.cooldown` (seconds). Bypassable with `plsdonate.donate.bypasscooldown`

#### 2. Confirmation Gate (`config: donate.confirmation`)

**If false**: No confirmation needed. `processDonation()` called immediately, cooldown set.

**If true** (default, and most common):
- The plugin first checks if the player is Bedrock — if yes, delegates to `BedrockFormHandler.sendConfirmationForm()` (bypasses MD5 hash entirely — see Layer 2 Section 2 below)
- For Java players:
  1. Generates an MD5 hash via `HashUtils.md5()` from: `playerUUID + "-" + timestamp + "-" + amount + "-" + email + "-" + method`
  2. Stores a `DonationRequest(playerUuid, amount, email, method, message)` in `pendingRequests` ConcurrentHashMap keyed by the MD5 hash
  3. Clears any previous pending requests from the same player
  4. Checks if the player's Java client supports dialogs (1.21.6+):
     - **Yes**: Opens native confirmation dialog via `JavaDialogHandler.openConfirmationDialog()` — displays summary with Yes/Cancel buttons. Yes button executes `/donate <hash>` command automatically
     - **No**: Sends chat confirmation with clickable `[Yes]` button that executes `/donate <hash>`

#### 3. Confirmation via MD5 Hash

When `/donate <md5hash>` is executed (triggered by clicking Yes or via dialog action):
1. Hash is looked up in `pendingRequests`
2. Validated that the request belongs to the same player UUID
3. Request removed from pending map (one-time use)
4. **Cooldown set** for the player
5. `processDonation()` called with the stored parameters

#### 4. `processDonation()` (static method, `DonateCommand.java:306`)

1. Plays `sound-effects.donation-processed` sound
2. Calls `DonationPlatform.createDonation()` which makes an async HTTP POST to tako.id API
3. On success:
   - Records transaction in SQLite via `TransactionRepository.createDonationRequest()` as PENDING with `donor_uuid`
   - Sends payment email via `EmailManager.sendPaymentEmail()` containing the payment URL
   - Notifies player with donation-email-sent message
4. On failure:
   - Shows the API error message to the player

---

### Layer 2, Section 1: Java Dialog UI (1.21.6+)

**Entry point**: `DonateCommand.onCommand()` when `args.length == 0` AND `ProtocolVersionUtil.supportsDialogs(player)` returns true AND `JavaDialogHandler` is initialized (server >= 1.21.6).

**Flow**:

1. **Cooldown check** (same as Layer 1)
2. `JavaDialogHandler.openDonationForm(player)` is called
3. A `PaperMultiActionDialog` is built with:
   - Text input for **Amount** (max 10 chars)
   - Text input for **Email** (max 100 chars)
   - Single-option input for **Payment Method** (qris/gopay/paypal with configurable labels)
   - Text input for **Message** (max 100 chars, optional)
   - **Submit** button: configured with `dynamicRunCommand("donate $(amount) $(email) $(method) $(message)")` — when clicked, it executes `/donate <amount> <email> <method> <message>` with the form field values substituted in
   - **Cancel** button: closes dialog (exitAction)
   - Optional display item (configurable material, size)
   - Optional header text (MiniMessage format)
4. When Submit is clicked, the command `/donate <amount> <email> <method> <message>` is dispatched — **this falls back to Layer 1**, which runs validation and, if confirmation is enabled, shows either a Java confirmation dialog or chat confirmation

**Confirmation Dialog** (`JavaDialogHandler.openConfirmationDialog()`):
- Built when Layer 1 detects confirmation is needed and player supports dialogs
- Shows summary of donation details
- Yes button executes `/donate <hash>` (static `runCommand`)
- Cancel button closes dialog
- Same as chat confirmation, but native UI

**Admin Confirmation Dialog** (`JavaDialogHandler.openAdminConfirmationDialog()`):
- Uses the same lang keys as regular dialog (`donation-confirmation-java-dialog.*`)
- Title has ` (fake)` or ` (push)` suffix appended based on `isSandbox`
- Yes button executes `/pdn <subcommand> <hash>`

---

### Layer 2, Section 2: Bedrock Forms UI (Floodgate)

**Entry point**: `DonateCommand.onCommand()` when `args.length == 0` AND `BedrockFormHandler` is initialized AND `isBedrockPlayer(player)` returns true.

**Flow**:

1. **Cooldown check** (same as Layer 1)
2. `BedrockFormHandler.openDonationForm(player)` is called
3. A `CustomForm` is built and sent via `FloodgateApi.getInstance().sendForm()` with:
   - Input field: **Amount** (free text)
   - Input field: **Email** (free text)
   - Dropdown: **Payment Method** (qris/gopay/paypal with configurable display names)
   - Input field: **Message** (free text, optional)
4. `validResultHandler` fires when the form is submitted:
   - Builds command string: `"donate " + amount + " " + email + " " + method + (message.isEmpty() ? "" : " " + message)`
   - Dispatches via `Bukkit.dispatchCommand(player, cmd)` — **falls back to Layer 1**
5. Layer 1 runs its validation. With confirmation enabled (default), it detects the player is Bedrock and calls `BedrockFormHandler.sendConfirmationForm()` — **NOT** the MD5 hash flow

**Confirmation Form** (`BedrockFormHandler.sendConfirmationForm()`):
- A `SimpleForm` (non-input, just display + buttons)
- Shows donation summary (amount, email, method, message) with Yes/No buttons
- When `isSimulation=true`, title has ` (fake)` or ` (push)` suffix
- Yes button handler:
  - **For simulation**: calls `processSimulatedDonation()` → `DonationService.fulfillSimulatedDonation()`
  - **For real**: calls `processBedrockDonation()` directly:
    1. Sets cooldown via `DonateCommand.setCooldown()`
    2. Plays donation-processed sound
    3. Calls `DonationPlatform.createDonation()` directly — **bypasses the MD5 hash flow entirely**
    4. On success: logs to DB (with donor_uuid), sends email, notifies player

**Key difference from Java Layer 2**: Bedrock confirmation does NOT use the MD5 hash pattern. The confirmation form receives the original parameters directly and calls `processBedrockDonation()` inline.

---

## Command Structure

### `/donate` (player-facing)

| Args | Behavior |
|------|----------|
| no args | Opens platform-native form/dialog (if supported), else shows help |
| `help` | Shows donation syntax guide |
| `<amount> <email> <method> [message]` | Layer 1: validates, runs confirmation flow (chat/dialog/form), calls API |
| `<32-char-md5-hash>` | Confirms a pending donation request, triggers API call |
| `top [page]` / `leaderboard [page]` | Shows cached top donors (cache size 50, served from `StatsManager`) |
| `milestone` | Shows donation goal progress |
| `history [page]` / `history <player> [page]` | Shows donation history (looks up by UUID for own history) |

### `/pdn` / `/plsdonate` (admin)

| Subcommand | Permission | Behavior |
|------------|-----------|----------|
| `help` | `plsdonate.admin.help` | Admin help menu |
| `fakedonate <amount> <email> <method> [msg]` | `plsdonate.admin.fakedonate` | Simulates sandbox donation (hidden from stats). Confirmation uses same lang keys as regular with ` (fake)` suffix |
| `pushdonate <amount> <email> <method> [msg]` | `plsdonate.admin.pushdonate` | Simulates live donation (included in stats). Confirmation uses same lang keys as regular with ` (push)` suffix |
| `transaction list/info/delete/setstatus/clear` | `plsdonate.admin.transaction` | Full CRUD on transaction records. Delete/clear use Java chat confirmation or Bedrock forms |
| `testdiscord` | `plsdonate.admin.testdiscord` | Sends test embed to all configured Discord webhooks |
| `reload` | `plsdonate.admin.reload` | Reloads config, lang, platform, reinitializes webhook. Runs `validateConfigValues()` which checks all numeric values |
| `leaderboard` / `milestone` | Same as /donate | Mirrors /donate functionality |

---

## Webhook Processing Flow (`WebhookManager.java`)

An embedded HTTP server listens on a configurable port (default 21172) and path (default `/plsdonate`).

1. Receives POST requests from tako.id when a payment is completed
2. Passes body + headers to `DonationPlatform.parseWebhook()`:
   - Validates `X-Tako-Signature` header using HMAC-SHA256
   - Parses JSON body for transaction ID, donor name, email, amount, message, payment method
3. **Integrity check**: `TransactionRepository.isTransactionValid()` verifies the transaction exists in the DB as PENDING and the checksum matches
4. **Claim check**: `TransactionRepository.claimTransaction()` atomically transitions PENDING → COMPLETED (replay attack prevention — only the first concurrent webhook wins)
5. On success: `DonationService.fulfillDonation()` is called (async on main thread) — this path does NOT perform DB ops (no `createDonationRequest` or `markTransactionUsed`), only notifications

### Sample tako.id webhook payload

```
POST / HTTP/1.1
Host: your-server:21172
User-Agent: node
Content-Type: application/json
X-Tako-Signature: 738653d7d70f34019b2ac34463fa26543e72b99effc6a32d9640ea510403104e

{"id":"9d27c9dc-1775-4fb6-b958-cf523291d020","type":"alert","message":"Ini adalah pesan contoh!","amount":100,"hasRecording":true,"gifUrl":"https://media.giphy.com/media/3o7aDcz3uEu9Y8XUO4/giphy.gif","pollingTitle":"Apa yang akan kamu lakukan?","pollingOptionId":"00478654-c796-44fb-a2bd-21f3794046d6","pollingOptionTitle":"Makan","soundboardName":"DUARR","soundboardSoundId":"baf5a152-ded4-41c4-a0b0-9c9998ccb44d","gifterName":"Siluman Taplak Meja","gifterEmail":"simanja@tako.id","creatorId":"540c3e77-3f72-4db2-888a-9e48a00c965a","creatorUsername":"tako","creatorName":"Tako","mediaType":"youtube","mediaId":"feq17t5myJ0","mediaStartTime":0,"createdAt":"2026-07-03T14:50:46.471Z","updatedAt":"2026-07-03T14:50:46.471Z","expiredAt":"2026-07-04T14:50:46.471Z"}
```

Fields consumed by `TakoPlatform.parseWebhook()`:
- `id` → transactionId
- `gifterName` / `name` → donorName
- `gifterEmail` / `email` → donorEmail
- `amount` → amount
- `message` → message
- `paymentMethod` → paymentMethod (may be absent in some payloads)

---

## Post-Donation Processing

### `DonationService.fulfillDonation()` (webhook path)
Called only by `WebhookManager`. Does NOT perform DB operations — the row was already created by the player's initial /donate request and claimed by `WebhookManager.claimTransaction()`. Handles:
1. **Stats refresh** (async)
2. **Broadcast**: If `donate.notification` is enabled, sends donation message + sounds to all online players
3. **Triggers**: `TriggersManager.processDonation()` evaluates trigger conditions and executes matching commands
4. **Discord**: `DiscordManager.sendDonation()` sends customizable Discord embed to configured webhooks

### `DonationService.fulfillSimulatedDonation()` (admin path)
Called by `BedrockFormHandler.processSimulatedDonation()` and `plsDonateCommand.executeSimulatedDonation()`. Handles:
1. **Database**: `createDonationRequest()` → async → `markTransactionUsed()` → `StatsManager.refreshSync()`
2. **Broadcast** + **Triggers** + **Discord** (same as fulfillDonation)

---

## Platform Architecture

`DonationPlatform` interface (`DonationPlatform.java`) abstracts the payment gateway:

- `createDonation()`: Returns `CompletableFuture<DonationResponse>` with success, message, transactionId, paymentUrl
- `parseWebhook()`: Parses and verifies incoming webhook payload
- `getMaxMessageLength()`: Platform-specific message length limit (190 for tako.id)

`TakoPlatform` (the only implementation):
- API base: `https://tako.id/api/gift/`
- Sends POST with JSON body: name, email, amount, paymentMethod, message
- Authorization: Bearer token from config
- Webhook verification: HMAC-SHA256 with configured webhook token
- HTTP client: `connectTimeout(15s)` + per-request `timeout(15s)`

---

## Database Schema (SQLite via HikariCP)

### `transactions` table
| Column | Type | Notes |
|--------|------|-------|
| id | INTEGER | Primary key, autoincrement |
| tx_id | TEXT | Unique, from payment platform |
| amount | REAL | Donation amount |
| donor_name | TEXT | Player name at time of donation |
| donor_uuid | TEXT | Player UUID (stable identifier across name changes) |
| checksum | TEXT | MD5(txId + amount + donorName) for integrity |
| status | TEXT | PENDING → COMPLETED (or VOID) |
| timestamp | INTEGER | Unix seconds |
| completed_at | INTEGER | Unix seconds, 0 if not completed |
| is_sandbox | INTEGER | 1 for sandbox/fake donations |

**UUID-based lookup**: When `donor_uuid` is available (all new transactions), history/total/rank queries match by UUID first. Old records with NULL `donor_uuid` fall back to `donor_name`. Leaderboard groups by `COALESCE(donor_uuid, donor_name)` so name changes don't split a player's total.

### `offline_triggers` table
| Column | Type | Notes |
|--------|------|-------|
| id | INTEGER | Primary key, autoincrement |
| player | TEXT | Lowercase player name |
| command | TEXT | Command to execute on join |

---

## Utility Classes

### `HashUtils.java`
Single `md5(String)` method replacing three identical implementations in `DonateCommand`, `plsDonateCommand`, and `TransactionRepository`.

### `DonationValidator.java`
Consolidates donation input validation (amount parsing, email, method, message) used by both `DonateCommand` and `plsDonateCommand`. Each method both validates AND sends the error message to the player, returning boolean/null.

### `ProtocolVersionUtil.java`
Per-player dialog support detection using ViaVersion API. Threshold: protocol >= 771 (Minecraft 1.21.6+). Fallback when ViaVersion API fails: `Integer.MAX_VALUE` (assumes latest).

---

## Key Architecture Points

1. **Unified command dispatch**: All UI flows (chat, Java dialog, Bedrock form) ultimately execute `/donate <args>` via `Bukkit.dispatchCommand()`. The dialog/forms are UX wrappers — they never bypass command validation.

2. **Two confirmation strategies**: Java players use the MD5 hash pattern (request stored in memory, confirmed by hash). Bedrock players skip the hash and call `processBedrockDonation()` directly from the confirmation form handler.

3. **Layer fallback chain**: Dialog 1.21.6+ → Chat (for pre-1.21.6 Java clients). Detected via ViaVersion per-player protocol checking. Protocol detection is done at command execution time (not on join), so the API is always available.

4. **Replay attack prevention**: Webhooks use an atomic compare-and-set (`UPDATE ... WHERE status = 'PENDING'`) that only the first concurrent caller wins. Combined with checksum validation, replay of the same webhook payload is impossible.

5. **Stats cache**: `StatsManager` keeps top 50 donors + total cached in memory, refreshed after every donation and admin ledger edit. Deep leaderboard pages (6+) hit the database.

6. **Pending request cleanup**: `PlayerQuitEvent` clears all pending MD5 hashes and cooldowns for the disconnected player.

7. **Permission model**: `/donate` uses `plsdonate.donate.*` permissions (default: true for most player-facing features). `/pdn` uses `plsdonate.admin.*` permissions (default: op).

8. **Config validation on reload**: `PlsDonate.validateConfigValues()` checks all numeric config values (amount min/max, cooldown, message length, port, milestone target/offset) during `/pdn reload` and at startup. Errors are sent to the player if triggered by a player, or only to console if triggered by console/startup.

9. **Two-tier DonationService**: `fulfillDonation()` (webhook path) has no DB ops — the row was already inserted and claimed. `fulfillSimulatedDonation()` (admin path) performs the full `INSERT → markUsed → refreshStats` chain. Separated to eliminate redundant DB operations on the webhook path.
