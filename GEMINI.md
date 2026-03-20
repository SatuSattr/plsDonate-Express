# GEMINI.md - plsDonate-Express

## Project Overview
**plsDonate-Express** is a high-performance PaperMC (Minecraft) plugin designed to automate real-money donations. It integrates with the **tako.id** platform to receive real-time donation updates via webhooks and execute automated rewards within the Minecraft server.

### Main Technologies
- **Java 21**: Utilizes modern Java features (records, pattern matching).
- **Paper API (1.21)**: Built for high-performance Minecraft servers.
- **Maven**: Dependency management and build lifecycle.
- **Sun HTTPServer**: Embedded lightweight server for receiving webhooks.
- **Geyser/Floodgate/Cumulus**: Specialized support for Bedrock Edition players, including custom UI forms.
- **JavaMail (SMTP)**: For dispatching payment links to Bedrock players via email.
- **Adventure / MiniMessage**: For rich, modern text formatting in-game.

---

## Architecture & Subsystems
The project follows a modular manager-based architecture:

- **PlsDonate.java**: The central entry point (Plugin Main). Handles lifecycle, subsystem initialization, and coordination.
- **DonationPlatform & TakoPlatform**: An abstraction layer for donation services. Currently implements the `tako.id` API.
- **WebhookManager**: Runs an asynchronous HTTP server on a configurable port to listen for incoming donation verifications.
- **TriggersManager**: The core automation engine. It parses `triggers.yml` to execute commands based on:
    - **Conditions**: Logic like `{amount} >= 50000` or `{player} has_permission 'rank.vip'`.
    - **Math Blocks**: Dynamic rewards using `{math: floor({amount} / 1000)}`.
    - **Offline Persistence**: Rewards for offline players are saved and executed automatically upon their next login.
- **StorageManager**: Handles data persistence for transaction logs and offline triggers using YAML-based storage in the `data/` directory.
- **EmailManager**: Handles SMTP configuration and dispatching. Used as a fallback for Bedrock players to receive payment links.
- **OverlayManager**: Communicates with an external Overlay API to fetch leaderboard and milestone data.
- **BedrockFormHandler**: Manages interactive Geyser/Floodgate forms for players on Bedrock Edition.

---

## Configuration & Resources
- `config.yml`: Global settings, API keys, webhook ports, and sound effects.
- `triggers.yml`: Definable reward logic with advanced conditions and placeholders.
- `lang/`: Multi-language support (default `en-US.yml`) using MiniMessage formatting.
- `templates/payment.html`: (Inferred) Template for email payment links.

---

## Development & Operations

### Building the Project
To build the plugin, ensure you have Java 21 and Maven installed:
```powershell
mvn clean package
```
The compiled JAR will be located in the `target/` directory.

### Running & Testing
1. Place the JAR in the `plugins/` folder of a Paper/Spigot server.
2. Configure `config.yml` with your `tako.id` API keys.
3. Open the configured webhook port (default `21172`) in your firewall.
4. Use `/donate <amount> [message]` in-game to initiate a donation.
5. Simulate or receive a webhook to verify the `TriggersManager` logic.

### Commands
- `/donate <amount> [message]`: Initiates a donation request.
- `/plsdonate reload`: Reloads all configurations and restarts the webhook listener.
- `/plsdonate leaderboard`: (If Overlay API is configured) Displays the donation leaderboard.
- `/plsdonate milestone`: (If Overlay API is configured) Displays current donation goals.

### Coding Conventions
- **Manager Pattern**: Logic should be encapsulated in a specific manager (e.g., `WebhookManager`) and accessed via `PlsDonate.java`.
- **Async Safety**: Webhook handling and API calls must be performed asynchronously to avoid stalling the main Minecraft server thread.
- **Command Injection Prevention**: User-provided strings (player names, messages) must be sanitized before being used in trigger commands.
- **Vanilla Prefixing**: All trigger commands should be prefixed with `minecraft:` to avoid conflicts with other plugins (e.g., EssentialsX).

---

## Key Files for Reference
- `src/main/java/click/sattr/plsDonate/PlsDonate.java`: Plugin entry point.
- `src/main/resources/triggers.yml`: Logic for donation rewards.
- `src/main/java/click/sattr/plsDonate/platform/DonationPlatform.java`: Platform interface.
- `src/main/java/click/sattr/plsDonate/ExpressionEvaluator.java`: Math and condition parsing logic.
