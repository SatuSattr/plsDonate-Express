# plsDonate-Express - Instructional Context

This file serves as the foundational mandate for all AI interactions within the `plsDonate-Express` project. It outlines the project's architecture, technologies, and development standards.

## Project Overview
`plsDonate-Express` is a high-performance, specialized Minecraft server plugin (for Paper/Spigot) designed to automate real-money donations and fulfillment. 

### Core Features
*   **Payment Integration**: Native integration with the **Tako.id** platform (QRIS, GoPay, PayPal).
*   **Webhook Listener**: Built-in HTTP server (default port `21172`) to receive real-time payment confirmations.
*   **Automated Triggers**: Robust system to execute console commands based on donation amount, message, or payment method, including support for math expressions (e.g., `{math: floor({amount} / 1000)}`).
*   **Offline Rewards**: Persistent storage (`offline_triggers.yml`) ensures rewards are delivered even if a player is offline during the donation.
*   **Bedrock Support**: Native UI support for Bedrock Edition players via Geyser/Floodgate.
*   **Email System**: SMTP-based email dispatching for sending payment links to players.
*   **Overlay API Integration**: Asynchronous fetching and caching of donation leaderboard and milestone data from a self-hosted Tako Overlay API.
*   **Replay Protection**: Transaction Ledger system to prevent duplicate reward fulfillment from the same donation ID.

## Technical Architecture
The plugin follows a modular, manager-based architecture:
*   **`PlsDonate`**: Central orchestrator and plugin entry point.
*   **`StorageManager`**: Manages YAML-based persistence for offline rewards and transaction ledger.
*   **`WebhookManager`**: Handles the internal HTTP server, webhook verification (HmacSHA256), and transaction ID validation against the ledger.
*   **`TriggersManager`**: Parses and executes rewards defined in `triggers.yml` with sanitized placeholders.
*   **`TakoPlatform`**: Implements the `DonationPlatform` interface for API communication.
*   **`EmailManager`**: Handles SMTP communication for transactional emails.
*   **`OverlayManager`**: Handles asynchronous communication and thread-safe caching for the self-hosted Tako Overlay API.

## Technology Stack
*   **Language**: Java 21
*   **API**: Paper API (1.21+)
*   **Dependencies**: 
    *   ConfigUpdater (Configuration management)
    *   Floodgate & Cumulus (Bedrock UI support)
    *   JavaMail (SMTP)
    *   Gson (JSON processing)
*   **Build System**: Maven

## Development & Build Commands
*   **Build Project**: `mvn clean package`
*   **Output**: The compiled JAR is located in the `target/` directory.

## Development Conventions
*   **Command Prefixes**: When defining triggers, always prefix vanilla commands with `minecraft:` (e.g., `minecraft:give`) to ensure compatibility.
*   **Transaction Integrity**: Every donation request must be logged in `transactions.yml` with a checksum (MD5 of txId + amount + name) to prevent replay attacks. Webhooks must verify the status before execution.
*   **Sanitization**: User-provided placeholders like `{message}` and `{player}` in triggers MUST be sanitized (escaped double quotes and backslashes) before command execution.
*   **Precision Math**: Use epsilon-based comparisons (e.g., `0.01` for IDR) in `ExpressionEvaluator` for floating-point currency checks.
*   **Configuration Safety**: `ConfigUpdater` is used for `config.yml` and `lang/` files, but **MUST NOT** be used for `triggers.yml` to prevent accidental deletion of custom user-defined triggers.
*   **Asynchronous Operations**: All API calls and heavy I/O (like `OverlayManager` fetches) must be performed asynchronously to avoid blocking the main server thread.
*   **Surgical Edits**: When modifying the codebase, prioritize maintaining the manager-based abstraction layers.

## Key Files
*   `src/main/resources/config.yml`: Main plugin configuration.
*   `src/main/resources/triggers.yml`: Reward/fulfillment definitions.
*   `src/main/resources/lang/en-US.yml`: Localized messages.
*   `src/main/java/click/sattr/plsDonate/StorageManager.java`: YAML persistence logic.
*   `src/main/java/click/sattr/plsDonate/OverlayManager.java`: Overlay API interaction logic.
*   `data/offline_triggers.yml`: Persistent storage for offline rewards (Do not edit manually).
*   `data/transactions.yml`: Ledger of donation requests and completed transactions (Do not edit manually).
