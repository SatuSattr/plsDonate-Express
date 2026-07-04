![plsDonate Banner](https://i.imgur.com/ZNpXdLN.png)

# 🎁 plsDonate

<p align="left">
  <a href="README.md"><img src="https://img.shields.io/badge/lang-English-blue?style=for-the-badge" alt="English"></a>
  <a href="README_ID.md"><img src="https://img.shields.io/badge/lang-Bahasa%20Indonesia-red?style=for-the-badge" alt="Bahasa Indonesia"></a>
</p>

**plsDonate** adalah plugin Minecraft yang mengintegrasikan server Anda dengan **Tako.id** untuk menerima donasi secara langsung melalui perintah (command) di dalam game.

> 🚧 **Pre-release** — plsDonate belum tersedia secara umum. Tautan unduhan akan muncul di sini setelah rilis pertama diluncurkan.

---

## Mengapa plsDonate?

<table>
<tr>
<td width="60">
<img src="https://i.imgur.com/taDjJcc.png" width="120" alt="Flexible">
</td>
<td>

<b>Triggers Donasi</b><br>
Jalankan satu atau lebih perintah secara otomatis setiap kali donasi diterima
Dilengkapi dengan conditional dan mathematical effect pada tiap trigger.

</td>
</tr>

<tr>
<td width="60">
<img src="https://i.imgur.com/EtPNpT4.png" width="120" alt="Setup">
</td>
<td>

<b>Integrasi Tako</b><br>
Gaperlu lagi daftar payment gateway yang rumit dan approval nya lama
Cukup buat akun Tako.id, salin API Key Anda, dan mulai menerima donasi.

</td>
</tr>

<tr>
<td width="60">
<img src="https://i.imgur.com/q7A0UJD.png" width="120" alt="Flow">
</td>
<td>

<b>Pengalaman Pemain yang Mulus</b><br>
Pemain membuat permintaan donasi langsung di dalam game. Tautan pembayaran secara otomatis dikirimkan ke email mereka tanpa memerlukan formulir eksternal.

</td>
</tr>
</table>

## Dukungan Cross-Platform

**plsDonate** menawarkan pengalaman native untuk Java dan Bedrock. Pemain Bedrock yang terhubung melalui [GeyserMC](https://geysermc.org/) dan [Floodgate](https://geysermc.org/wiki/floodgate/) akan secara otomatis menerima formulir interaktif yang intuitif, sementara pemain Java (1.21.6+) menggunakan dialog native.

<table>
<tr>
<th align="center"> Edisi Bedrock</th>
<th align="center"> Edisi Java (1.21.6+)</th>
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

> Pemain Java dengan versi dibawah 1.21.6 akan menggunakan Interactive Chat Dialog

## Persyaratan

- **Paper** 1.21 - 26.1
- **Java** 21+
- Akun **[Tako.id](https://tako.id/)**
- _Opsional_ — [GeyserMC](https://geysermc.org/) + [Floodgate](https://geysermc.org/wiki/floodgate/) untuk dukungan Bedrock
- _Opsional_ — [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) untuk placeholder `%plsdonate_*%`

## Perintah & Izin

<table>
<tr>
<th align="left">Command</th>
<th align="left">Deskripsi</th>
<th align="left">Permission</th>
<th align="left">Default</th>
</tr>
<tr>
<td><code>/donate &lt;amount&gt; &lt;email&gt; &lt;method&gt; [msg]</code></td>
<td>Memulai permintaan donasi di dalam game</td>
<td><code>plsdonate.donate.request</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/donate top [page]</code></td>
<td>Menampilkan donatur teratas</td>
<td><code>plsdonate.donate.top</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/donate milestone</code></td>
<td>Menampilkan kemajuan menuju target donasi</td>
<td><code>plsdonate.donate.milestone</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/donate help</code></td>
<td>Menampilkan bantuan penggunaan perintah /donate</td>
<td><code>plsdonate.donate.help</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/pdn top [page]</code></td>
<td>Menampilkan donatur teratas <em>(admin)</em></td>
<td><code>plsdonate.donate.top</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/pdn milestone</code></td>
<td>Menampilkan kemajuan menuju target donasi <em>(admin)</em></td>
<td><code>plsdonate.donate.milestone</code></td>
<td><code>true</code></td>
</tr>
<tr>
<td><code>/pdn help</code></td>
<td>Menampilkan daftar perintah yang tersedia <em>(admin)</em></td>
<td><code>plsdonate.admin.help</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn transaction</code></td>
<td>Mengelola catatan transaksi <em>(admin)</em></td>
<td><code>plsdonate.admin.transaction</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn fakedonate &lt;amount&gt; &lt;email&gt; &lt;method&gt; [msg]</code></td>
<td>Simulasikan donasi sandbox, tidak termasuk dalam statistik <em>(admin)</em></td>
<td><code>plsdonate.admin.fakedonate</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn pushdonate &lt;amount&gt; &lt;email&gt; &lt;method&gt; [msg]</code></td>
<td>Simulasikan donasi asli, termasuk dalam statistik <em>(admin)</em></td>
<td><code>plsdonate.admin.pushdonate</code></td>
<td><code>op</code></td>
</tr>
<tr>
<td><code>/pdn reload</code></td>
<td>Memuat ulang konfigurasi <em>(admin)</em></td>
<td><code>plsdonate.admin.reload</code></td>
<td><code>op</code></td>
</tr>
</table>

`/plsdonate` dan `/pdn` dapat digunakan secara bergantian.

### Izin Lainnya

<table>
<tr>
<th align="left">Permission</th>
<th align="left">Deskripsi</th>
<th align="left">Default</th>
</tr>
<tr>
<td><code>plsdonate.donate.bypasscooldown</code></td>
<td>Melewati waktu tunggu (cooldown) donasi</td>
<td><code>op</code></td>
</tr>
</table>

## Pemasangan & Pengaturan

Panduan pengaturan lengkap — termasuk penerapan produksi (production deployment) dan pengujian lokal dengan ngrok — tersedia di **[Wiki](https://github.com/SatuSattr/plsDonate/wiki)**.
