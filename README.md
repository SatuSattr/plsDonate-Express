![plsDonate Banner](https://i.imgur.com/ZNpXdLN.png)

# 🎁 plsDonate

<p align="left">
  <a href="README.md"><img src="https://img.shields.io/badge/lang-English-blue?style=for-the-badge" alt="English"></a>
  <a href="README_ID.md"><img src="https://img.shields.io/badge/lang-Bahasa%20Indonesia-red?style=for-the-badge" alt="Bahasa Indonesia"></a>
</p>

**plsDonate** is a Minecraft plugin that integrates your server with **Tako.id** to receive donations directly from the in-game command.

> 🚧 **Pre-release** — plsDonate isn't publicly available yet. Download links will appear here once the first release ships.

---

## Why plsDonate?

<table>
<tr>
<td width="60">
<img src="https://i.imgur.com/taDjJcc.png" width="120" alt="Flexible">
</td>
<td>

<b>Donation Triggers</b><br>
Execute commands automatically whenever a donation is received.
Equiped with conditional and mathematical effect in every trigger.

</td>
</tr>

<tr>
<td width="60">
<img src="https://i.imgur.com/EtPNpT4.png" width="120" alt="Setup">
</td>
<td>

<b>Tako Integration</b><br>
Skip complicated payment gateway registration. Simply create a Tako.id account, paste your API credentials, and start accepting donations within minutes.

</td>
</tr>

<tr>
<td width="60">
<img src="https://i.imgur.com/q7A0UJD.png" width="120" alt="Flow">
</td>
<td>

<b>Seamless Player Experience</b><br>
Players create donation requests directly in-game. Payment links are automatically delivered to their email without requiring external forms.

</td>
</tr>
</table>

## Cross Platform Support

**plsDonate** offers a native experience for both Java and Bedrock. Bedrock players connected through [GeyserMC](https://geysermc.org/) and [Floodgate](https://geysermc.org/wiki/floodgate/) will automatically receive beautiful interactive forms, while Java players using interactive chat-based interface.

<table>
<tr>
<th align="center"> Bedrock Edition</th>
<th align="center"> Java Edition (1.21.6+)</th>
</tr>
<tr>
<td align="center">
<img src="https://raw.githubusercontent.com/wiki/SatuSattr/plsDonate/images/donation_form_bedrock.png" width="400" alt="Bedrock Screenshot">
</td>
<td align="center">
<img src="https://raw.githubusercontent.com/wiki/SatuSattr/plsDonate/images/donation_form_java.png" width="400" alt="Java Screenshot">
</td>
</tr>
<tr>
<td align="center">
<img src="https://raw.githubusercontent.com/wiki/SatuSattr/plsDonate/images/donation_confirmation_bedrock.png" width="400" alt="Bedrock Screenshot">
</td>
<td align="center">
<img src="https://raw.githubusercontent.com/wiki/SatuSattr/plsDonate/images/donation_confirmation_java.png" width="400" alt="Java Screenshot">
</td>
</tr>
</table>

> Java player with version below 1.21.6 will fallback to Interactive Chat Dialog

## Requirements

- **Paper** 1.21 - 26.1
- **Java** 21+
- A **[Tako.id](https://tako.id/)**
- _Optional_ — [GeyserMC](https://geysermc.org/) + [Floodgate](https://geysermc.org/wiki/floodgate/) for Bedrock support
- _Optional_ — [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) for `%plsdonate_*%` placeholders

## Commands & Permissions

<table>
<tr>
<th align="left">Command</th>
<th align="left">Description</th>
<th align="left">Permission</th>
<th align="left">Default</th>
</tr>
<tr>
<td><code>/donate &lt;amount&gt; &lt;email&gt; &lt;method&gt; [msg]</code></td>
<td>Start a donation request in-game</td>
<td><code>plsdonate.donate.request</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/donate top [page]</code></td>
<td>Show the top donators</td>
<td><code>plsdonate.donate.top</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/donate milestone</code></td>
<td>Show progress toward the donation goal</td>
<td><code>plsdonate.donate.milestone</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/donate help</code></td>
<td>Show help in using the /donate command</td>
<td><code>plsdonate.donate.help</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/pdn leaderboard [page]</code></td>
<td>Show the top donators (alias: <code>/pdn top</code>) <em>(admin)</em></td>
<td><code>plsdonate.donate.top</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/pdn milestone</code></td>
<td>Show progress toward the donation goal <em>(admin)</em></td>
<td><code>plsdonate.donate.milestone</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/pdn help</code></td>
<td>List available commands <em>(admin)</em></td>
<td><code>plsdonate.admin.help</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn transaction</code></td>
<td>Manage transaction records <em>(admin)</em></td>
<td><code>plsdonate.admin.transaction</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn fakedonate &lt;amount&gt; &lt;email&gt; &lt;method&gt; [msg]</code></td>
<td>Simulate a sandbox donation, excluded from stats <em>(admin)</em></td>
<td><code>plsdonate.admin.fakedonate</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn pushdonate &lt;amount&gt; &lt;email&gt; &lt;method&gt; [msg]</code></td>
<td>Simulate a real donation, included in stats <em>(admin)</em></td>
<td><code>plsdonate.admin.pushdonate</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn reload</code></td>
<td>Reload the configuration <em>(admin)</em></td>
<td><code>plsdonate.admin.reload</code></td>
<td><code>op</code></td>
</tr>
</table>

`/plsdonate` and `/pdn` are interchangeable.

### Other Permissions

<table>
<tr>
<th align="left">Permission</th>
<th align="left">Description</th>
<th align="left">Default</th>
</tr>
<tr>
<td><code>plsdonate.donate.bypasscooldown</code></td>
<td>Bypass the donation cooldown</td>
<td><code>op</code></td>
</tr>
</table>

## Setup & Installation

Full setup guides — including production deployment and local testing with ngrok — live in the **[Wiki](https://github.com/SatuSattr/plsDonate/wiki)**.
