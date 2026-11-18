# DarijaVoiceBot 🇲🇦🎧

A Paper **1.21.11** plugin that runs a Discord bot. The bot joins your voice channel,
**listens** to people, **answers in Moroccan Darija**, and **speaks** the answer out loud.

```
🎤 You talk  →  Whisper (Groq) turns it into text  →  Gemini writes a Darija answer
            →  Microsoft Edge voice (ar-MA) reads it  →  🔊 the bot talks in voice chat
```

Everything uses **free** services.

---

## ⚠️ Requirements

| What | Why |
|---|---|
| **Java 25** on your Minecraft server | Discord now encrypts all voice (DAVE). The library that handles that needs Java 25. Paper 1.21.11 runs fine on Java 25. In most panels: **Startup → Docker Image / Java version → Java 25**. |
| Linux x86-64 or ARM64 host (or Windows) | Native voice libraries |
| Host allows outbound **UDP** | Discord voice uses UDP. Almost all hosts allow it. If the bot joins but never talks or hears, ask your host. |

If the server runs an older Java, the plugin still loads but prints a big red message telling you to switch to Java 25.

---

## 1. Build the jar (no coding needed, uses GitHub)

1. Make a free account on <https://github.com> and create a **new repository** (private is fine).
2. Click **"uploading an existing file"** and drag **all the files and folders from this zip** into it (including the `.github` folder). Commit.
   *Tip: if the `.github` folder doesn't upload by drag-and-drop, use GitHub Desktop, or create the file `.github/workflows/build.yml` by hand and paste its content.*
3. Go to the **Actions** tab. A build named **"Build plugin"** starts automatically. If it doesn't, click it, then **Run workflow**.
4. When it shows a green ✅ (about 2 minutes), open it and download **DarijaVoiceBot-plugin** under *Artifacts*. Unzip it and you have `DarijaVoiceBot-1.0.0.jar`.

**Building on your own PC instead:** install JDK 25, open a terminal in this folder and run `./gradlew build` (Windows: `gradlew.bat build`). The jar appears in `build/libs/`.

---

## 2. Create the Discord bot

1. Go to <https://discord.com/developers/applications> → **New Application**.
2. **Bot** tab → **Reset Token** → copy the token (keep it secret!).
3. **OAuth2 → URL Generator**:
   - Scopes: `bot` and `applications.commands`
   - Bot permissions: `View Channels`, `Send Messages`, `Connect`, `Speak`, `Use Voice Activity`
4. Open the generated link and invite the bot to your server.

No privileged intents are needed.

## 3. Get the free API keys

- **Groq** (hearing): <https://console.groq.com/keys> → Create API key
- **Gemini** (brain): <https://aistudio.google.com/apikey> → Create API key

Free tiers have daily limits. They're plenty for a group of friends, but limits can change, so check the sites.

## 4. Install

1. Put `DarijaVoiceBot-1.0.0.jar` in your server's `plugins/` folder and start the server once.
2. Open `plugins/DarijaVoiceBot/config.yml` and fill in:
   - `discord.token`
   - `discord.guild-id` (right-click your server icon → *Copy Server ID*; turn on Developer Mode in Discord settings first). This makes the slash commands appear instantly.
   - `speech-to-text.groq-api-key`
   - `ai.gemini-api-key`
3. In the console or in-game run: `/darijabot reload`

---

## Using it

### In Discord
| Command | What it does |
|---|---|
| `/join` | Bot joins **your** voice channel and starts listening |
| `/leave` | Bot leaves |
| `/ask <question>` | Ask by text, it answers in text **and** voice |
| `/say <text>` | Bot reads your text out loud |
| `/reset` | Bot forgets the conversation |

Then just talk! Wait about 1 second after you finish a sentence; the bot answers in ~2–4 seconds.

### In Minecraft (ops only)
| Command | What it does |
|---|---|
| `/darijabot status` | Is the bot online? |
| `/darijabot reload` | Reload config and restart the bot |
| `/darijabot say <text>` | Speak in the Discord voice channel |
| `/darijabot ask <question>` | Ask the AI from in-game; the answer shows in chat and is spoken in Discord |
| `/darijabot reset` / `leave` | Clear memory / leave voice |

---

## Tuning (config.yml)

- **Many people in the channel?** Set `voice.require-wake-word: true`. The bot then only answers when someone says "بوت" / "bot".
- **Bot reacts to noise / keyboard?** Raise `voice.speech-threshold` (e.g. 800–1200).
- **Bot cuts you off mid-sentence?** Raise `voice.silence-ms` (e.g. 1200).
- **Female voice:** `text-to-speech.voice: "ar-MA-MounaNeural"`
- **Talk faster:** `text-to-speech.rate: "+15%"`
- **Personality:** edit `ai.system-prompt` (written in Darija). Keep the line asking for Arabic letters, because the Moroccan voice reads Arabic script much better than "3arabizi".
- **People mix French/English?** Set `speech-to-text.language: ""` to let Whisper auto-detect.
- **Log conversations** to a text channel: set `discord.log-channel-id`. To show them in Minecraft chat: `minecraft.broadcast-to-chat: true`.
- **Gemini limit reached?** Switch `ai.provider: "groq"`. It uses your Groq key.

## Troubleshooting

| Problem | Fix |
|---|---|
| Red "needs Java 25" message | Change your server's Java version to 25 in the host panel |
| Slash commands don't show | Set `discord.guild-id`, then `/darijabot reload`, and restart your Discord app |
| Bot joins but is silent / deaf | Host may block UDP. Also check the bot has *Connect* + *Speak* permissions |
| `Speech-to-text failed (HTTP 401)` | Wrong Groq key |
| `AI (Gemini) failed (HTTP 429)` | Free limit reached; wait, or switch provider to groq |
| `Edge voice failed` in console | The Microsoft voice service changed; the bot uses the Google voice instead. Update the plugin later |

## Privacy

The bot listens to everyone in the voice channel and sends their speech to Groq (and the text to Google/Groq).
Tell your server members, e.g. in the channel description.
