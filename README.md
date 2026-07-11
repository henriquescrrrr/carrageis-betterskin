# BetterSkin

**Browser-based skin management for offline-mode Minecraft servers.**

Drop the plugin JAR into your `plugins/` folder — that's it! No external software needed.

---

## How It Works

1. Player joins your server (or types `/skin`)
2. They get a **clickable link** in chat
3. They open the link in their browser
4. They pick/edit/import a skin in the browser
5. The skin applies automatically (instantly or on next join)

**Everything runs inside the plugin:**
SQLite database (stored in `plugins/BetterSkin/betterskin.db`)
Built-in web server (serves the browser UI)
MineSkin API integration (for signing skins)

**This plugin is designed for `online-mode: false` servers.** Skins are signed via MineSkin and injected server-side — Mojang is not involved.

---

## Resource Usage

This plugin is very lightweight:
- **RAM**: ~5–15 MB extra (SQLite + tiny embedded HTTP server)
- **CPU**: Essentially zero. Network calls to MineSkin are the only "work", and those happen rarely.
- **Disk**: The database file stays tiny — a few KB to a couple MB even with hundreds of players.

Safe for limited/low-spec servers.

---

## Quick Start

### 1. Install the Plugin

```
1. Build with: mvn clean package
2. Copy target/betterskin-1.0.jar to your server's plugins/ folder
3. Start/restart your server
```

### 2. Get a MineSkin API Key (Required for custom skins)

1. Go to **https://account.mineskin.org/keys**
2. Create a free account
3. Generate an API key
4. Put it in `plugins/BetterSkin/config.yml`:
   ```yaml
   mineskin:
     api-key: "YOUR_KEY_HERE"
   ```

### 3. Set Your Server's Public URL

Edit `plugins/BetterSkin/config.yml` and change the `public-url` to your server's IP:

```yaml
server:
  port: 4567
  public-url: "http://YOUR_SERVER_IP:4567"
```

**Examples:**
- Home server: `http://192.168.1.100:4567`
- VPS with IP: `http://123.45.67.89:4567`
- Domain name: `http://play.myserver.com:4567`
- Testing on your PC: `http://localhost:4567`

### 4. Restart and Play!

Players will see a link when they join. They click it, pick a skin, done!

---

## FAQ

### What port does the website run on?
By default, port **4567**. You can change it in `config.yml` under `server.port`.

### What URL do players see?
Whatever you put in `server.public-url`. This should be your server's IP + the port.

### Do I need to install Node.js / Docker / PostgreSQL?
**No!** Everything runs inside the plugin. It uses SQLite (a file in the plugins folder) and Java's built-in HTTP server.

### Does this work with `online-mode: false`?
**Yes — that's exactly what it's for.** Since offline-mode servers can't use Mojang skins, this plugin signs skins via MineSkin and injects them server-side. No Mojang account needed.

### What is MineSkin and why do I need it?
MineSkin is a free service that "signs" skin images so Minecraft accepts them. Without it, the game would reject custom skins. Get a free API key at https://account.mineskin.org/keys

### Can I import skins from premium accounts?
Yes! The "Import from Username" feature fetches skins from Mojang's API. The player's skin is imported with its original Mojang signature — no MineSkin call needed for those.

### How does instant skin apply work?
When `skin.apply-mode` is set to `INSTANT` (the default), the skin is applied immediately when the player changes it in the browser — no need to rejoin.

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/skin` | `betterskin.use` | Shows the browser link |
| `/skin status` | `betterskin.use` | Shows current skin info |
| `/skin reload` | `betterskin.admin` | Reloads config & lang files |

---

## Configuration

### `plugins/BetterSkin/config.yml`

```yaml
server:
  port: 4567                                    # Web server port
  public-url: "http://YOUR_SERVER_IP:4567"     # URL players see

mineskin:
  api-key: ""                                   # Your MineSkin API key

token:
  expiry-seconds: 300                           # Link valid for 5 minutes
  bind-ip: false                                # Bind token to player IP

join:
  send-link: true                               # Auto-send link on join
  link-delay-ticks: 40                          # Wait 2s before sending

skin:
  apply-mode: "INSTANT"                         # INSTANT or ON_JOIN
  cache-ttl-seconds: 600                        # Memory cache duration

locale: "pt_PT"                                 # en_US | pt_PT (shipped default is pt_PT)
```

---

## Web Features

- **Dashboard** — 3D skin preview, skin history, quick actions
- **Skin Editor** — 64×64 pixel editor with pencil, eraser, fill, color picker, undo/redo
- **Import** — From Minecraft username, URL, or file upload
- **Presets** — Server-wide and personal skin presets

---

## Project Structure

```
src/main/java/pt/henrique/webskins/
├── WebSkins.java              — Main plugin
├── auth/TokenManager.java     — Token generation
├── command/SkinCommand.java   — /skin command
├── config/ConfigManager.java  — Config reader
├── config/LangManager.java    — Localization
├── database/Database.java     — SQLite database
├── listener/                  — Join/quit listeners
├── model/SkinData.java        — Skin data record
├── skin/SkinApplier.java      — Apply skins to players
├── skin/SkinCache.java        — Memory cache
└── web/
    ├── WebServer.java         — Embedded HTTP server
    ├── handler/               — API route handlers
    └── service/               — MineSkin, import, image validation

src/main/resources/
├── config.yml
├── plugin.yml
├── lang/en_US.yml
├── lang/pt_PT.yml
└── web/                       — Frontend (HTML/CSS/JS)
    ├── index.html
    └── assets/
```

---

## License

MIT

