# Beacon

A custom Android home-screen launcher built around an animated "orb" avatar with a personality of its own.

Beacon replaces the stock home screen with a single living character: an orb that glances around when idle, perks up on events, reacts to notifications and calls, and shifts mood with the time of day. Everything else (the app ring, drawer, search, and settings) lives around it. It's a personal project, built for one phone and one person, and published as a portfolio piece.

<!-- screenshots here -->

## Features

- **Animated orb avatar.** A custom Canvas animation engine draws the orb at 60 fps. It has three personality layers:
  - *Curious when idle:* eye darts, glances, perk-ups, and fidgets on randomized timers.
  - *Intense on events:* it bounces, leans, and flares its glow for notifications, wake words, and "thinking".
  - *Moody by time of day:* livelier in the morning, sleepy at night, and asleep with a dimmed glow when the screen is off.
- **Orb companion.** Each theme gives the orb its own personality: short activities, idle beats every 4 to 6 seconds (about a third of them full activities), reactions to a two-finger poke or pet, tilt-to-roll, and occasional chat bubbles with daily caps and quiet hours. Everything runs locally.
- **Three themes.** *Matrix* has green code-rain and solid glyph icons. *Neon City* has cyan and magenta outlines, an optional glitch effect, and a waveform call halo. *ODST* is an eyes-first civic face with amber public-address panels, a rainy city backdrop, and an optional VISR-style night outline mode.
- **Gesture home.** Tap the orb to start your assistant through Android's own assist session (the same path as holding Home), so the full assistant app doesn't take over the screen. This needs your agent app set as the default digital assistant (see [Build](#build)). Hold it to open a ring of pinned apps. Flick it for quick actions you choose (camera, torch, and so on). Swipe up for the app drawer, swipe down for search, and long-press the background for settings.
- **Notification bubbles.** New notifications pop out of the orb. Tap one to open it or swipe it away to clear it.
- **Offline voice wake word.** On-device speech recognition with [Vosk](https://alphacephei.com/vosk/) listens for a wake word only while the screen is on and unlocked. No audio leaves the phone.
- **Built-in themed keyboard** for the home "message" box, with autocorrect, a word engine, and an emoji panel.
- **Hourly weather tiles**: the next seven hours from [Open-Meteo](https://open-meteo.com/), styled per theme. No API key is needed. With "Where I am" selected, the row shows "Location off" or "Location not gathered" instead of falling back to a fixed spot.
- **Works with any agent.** A swappable [agent connector](#connectors--use-with-any-agent) lets the home message box talk to any agentic system that accepts an HTTPS webhook. You configure the endpoint, auth, and payload. No agent is built in.
- **Optional remote updates** from your own PC through a relay you run. The phone only offers an update that is the same app, signed with the same key, and newer, and Android's own Install prompt always asks you first.
- **Battery saver and Reduce motion** modes.

## Tech

- **Kotlin** and the Android SDK (min SDK 33, target SDK 35). The launcher uses plain Views and the keyboard uses Jetpack Compose.
- **Custom Canvas animation engine** (`Orb.kt`, `Personality.kt`, `Rain.kt`, `StageView.kt`). It runs to a per-frame performance budget that the tests enforce.
- **Unit-tested logic.** About 240 JVM/Robolectric tests cover the orb personality, gestures, ring layout, wake-word state machine, keyboard engine, webhook sealing, and the companion-link policy.
- **Security-minded design.**
  - Secrets are sealed with Android Keystore (AES-GCM, plus RSA-OAEP for one-time imports).
  - `allowBackup=false`.
  - The webhook only accepts HTTPS.
  - The bridge uses an outbound-only TLS 1.3 channel with certificate pinning and end-to-end authenticated commands.
  - A fixed allowlist limits what the bridge can do.
- **Signed release builds.** You sign with your own keystore, supplied through environment variables or a gitignored properties file. No signing material is in this repo.

## Themes

| Theme | Look |
|-------|------|
| **Matrix** | Green-on-black code rain, solid glyph icons, and sound-wave bars during calls |
| **Neon City** | Cyan and magenta outlines, an optional glitch effect, and a waveform ring during calls |
| **ODST** | A sage-green eyes-first face with a HUD, amber public-address panels with an original 4-note chime, a rainy night skyline, and an optional night outline mode |
| **Holiday** | A spooky companion that swaps between four shaded characters (jack-o'-lantern, ghost, black cat, witch hat) with smoke, cauldron, or coin transitions, plus holiday weather tiles and keyboard. Turned on manually in Settings |

Wallpaper style, glyph set, density, speed, and color are all adjustable in Settings.

## Build

You need JDK 17 and the Android SDK. Set `ANDROID_HOME` or create a `local.properties` file with `sdk.dir=...`.

```bash
# Debug build (no signing needed)
./gradlew assembleDebug

# Tests and lint
./gradlew testReleaseUnitTest lintRelease
```

### Signed release

Create your own keystore. Never commit it.

```bash
keytool -genkeypair -v -keystore my-release.jks -alias beacon -keyalg RSA -keysize 2048 -validity 10000
```

Provide the signing values in one of two ways.

**Option A: environment variables.**

```bash
export BEACON_KEYSTORE=/path/to/my-release.jks
export BEACON_KEYSTORE_PASSWORD=...
export BEACON_KEY_ALIAS=beacon
export BEACON_KEY_PASSWORD=...
./gradlew assembleRelease
```

**Option B: a properties file.** Copy `keystore.properties.example` to `keystore.properties`, which is gitignored, and fill it in.

If neither is present, `assembleRelease` produces an unsigned APK.

Install the APK with `adb install -r app/build/outputs/apk/release/app-release.apk`. Then make Beacon your launcher in **Settings > Apps > Default apps > Home app**. The stock launcher stays installed, so you can switch back the same way.

**Required for voice calls:** set your agent app (for example Grok Bot) as the phone's default digital assistant in **Settings > Apps > Default apps > Digital assistant app**. Tapping the orb and saying "Hey Prime" start the call through Android's System assist APIs, which only open whatever app holds that slot. If no assistant is set, or a different one is, the call won't start in your agent app.

## Connectors / use with any agent

Beacon isn't tied to any one assistant. The home message box sends what you type through an **agent connector**, which has three parts that you set in **Settings > Agent connector**:

| Part | What you set |
|------|--------------|
| **Endpoint** | Your agent's HTTPS webhook URL. Plain `http://` is rejected. |
| **Auth** | The header name (default `Authorization`), the scheme (default `Bearer`, or blank to send the raw key), and the key. The key is sealed in Android Keystore and is never shown again, logged, or included in error messages. |
| **Payload mapping** | A JSON template with placeholders. Write them **unquoted**: Beacon inserts each value already JSON-encoded. |

Placeholders:

| Placeholder | Value |
|-------------|-------|
| `{{text}}` | The message (JSON string). Required. |
| `{{msg_id}}` | A random UUID (JSON string) |
| `{{ts}}` | Epoch milliseconds (JSON number) |
| `{{via}}` | Where the message was typed, `"beacon-home"` (JSON string) |

Built-in presets are editable starting points:

| Preset | Template |
|--------|----------|
| **Generic JSON** (default) | `{"text":{{text}},"msg_id":{{msg_id}},"ts":{{ts}},"source":"beacon","via":{{via}}}` |
| **Text only** | `{"text":{{text}}}` |
| **Chat messages** | `{"messages":[{"role":"user","content":{{text}}}],"metadata":{"msg_id":{{msg_id}},"via":{{via}}}}` |
| **Custom** | Any JSON that contains `{{text}}`. It's validated when you save. |

Any 2xx response counts as sent. A 401 or 403 shows as "refused the key". Any other failure keeps your text in the box so you can retry.

The code lives in `Connector.kt` (presets, templating, and validation) and `PrimeHook.kt` (transport and the sealed config store). To add a preset, append one `Preset(...)` entry.

### Example connector: Grok Bot (via the Emit companion)

This is only one example of wiring Beacon to an agent. A Grok Bot routine with a webhook trigger gives you a URL and a key. Then:

1. Choose the **Grok Bot via Emit companion (example)** preset. It posts the same body shape the Emit PC companion uses:
   ```json
   {"source":"matrix-sms","type":"prime_chat","kind":"prime_chat","from_self":true,
    "msg_id":{{msg_id}},"text":{{text}},"ts":{{ts}},"reply_with":"prime-reply \"<text>\" via your companion","via":{{via}}}
   ```
2. Paste **your** routine's webhook URL and key, with `Authorization` / `Bearer`.
3. Save, then type in the home box.

For any other agent (a self-hosted LLM agent, n8n, Zapier, Make, or your own server), use **Generic JSON** or write a template that matches its request body.

### One-time secure import (optional)

You can push the connector config to the phone instead of typing it. The app publishes an RSA public key (held in Android Keystore) to its own app folder. Your PC encrypts `{"url","key","header","scheme","connector" or "template"}` against that key with RSA-OAEP and AES-GCM and `adb push`es the ciphertext. The app imports it, seals the key, and deletes the file. See `HookImport.kt`.

### Phone Link (optional, agent-agnostic)

Phone Link is an outbound TLS 1.3 channel to a relay server **you** run. It lets an MCP-capable agent see the screen and send taps, swipes, and typing, within a fixed allowlist. You pair it by pasting the pairing file your relay generates (`{"host":"YOUR_RELAY_HOST","port":443,"fingerprint":"…","agent_key":"…"}`). The phone generates its own MCP access token, and the relay never stores it. The repo ships no host, port, certificate, or key.

Nothing in this repo points at any real server. Set your own values.

### Known limitation: the orb's app shortcuts

The connector covers messaging. The orb's *app* shortcuts still target one assistant app: tapping the orb opens it, the call halo watches it, and the optional hang-up helper is scoped to it. That app is set by the `Grok.PKG` constant and `res/xml/hangup_service.xml`. To use a different assistant app, change both and rebuild. Making this a runtime setting is on the roadmap.

### Remote updates (optional)

Beacon can receive a new APK from your PC while the phone is away from home. It reuses the line protocol of the Emit relay (`msms-relay`, a server **you** run) in its own room, and the relay only forwards AES-256-GCM ciphertext.

1. On the phone, open **Settings > Updates > Pair PC for updates** and copy the phone key.
2. On the PC, run `tools/beacon-pc/beacon-update.bat pair <phone key>` (or `python tools/beacon-pc/beacon_update.py pair ...`). It prints a `BCN1.` pairing code that is encrypted to that phone's Keystore key. The code is generated at pairing time from your own relay settings; none ship in this repo.
3. Paste the code into the dialog and tap **Pair**.
4. Push a build with `beacon-update push app-release.apk`. The phone verifies the package name, signing certificate, and version, then posts a notification. Tap it, then tap Install on Android's prompt.

The PC tool needs Python 3.9+ with `cryptography`, and reads the relay host, certificate pin, and token from the Emit PC companion's config. On Windows the local pairing key is protected with DPAPI. The phone side is in `app/src/main/java/.../update/`.

## Changelog

- **0.1.29:** The holiday set is four faces (jack-o'-lantern, ghost, cat, witch hat). The ghost and hat have no mouths, and the jack-o'-lantern keeps its original face.
- **0.1.28:** Holiday rendering fixes (blur radii, one-piece ghost body, bounded render cache). Avatar swaps happen at most every 20 minutes, and that timer survives restarts. Cuter faces.
- **0.1.27:** New Holiday theme with shaded characters, random smoke/cauldron/coin swap transitions, per-character antics and lines, and holiday weather tiles and keyboard. Optional two-way theme sync with a companion SMS app via an exported receiver guarded by a `signature|knownSigner` permission (set the peer app's signing-cert SHA-256 in `res/values/theme_sync.xml`). Companion dialogue no longer references studying or cooking; the study nudge is now a neutral break reminder.
- **0.1.26:** Weather tiles read hour, temperature, icon, then condition, with the temperature as a solid line above an unobstructed icon. Each theme gets its own tile skin (Matrix terminal, Neon City glow, ODST HUD). Cleaner cloud icon.
- **0.1.25:** Temperature readability and centering tweaks on the weather tiles.
- **0.1.24:** Weather tiles fit inside the date's margins and scale text at large font sizes.
- **0.1.23:** Seven hourly weather tiles under the date (Open-Meteo hourly forecast, live coarse location refreshed when Home opens). Notifications stack below the tiles without overlap.
- **0.1.22:** Chat bubbles join the idle beats (about one every 2 minutes, 90-second minimum gap, daily cap of 40; quiet hours unchanged). The call orb ends about 1 second after call audio stops.
- **0.1.21:** The call orb stops when the assist session ends (requires real call audio after returning home; 90-second weak-signal timeout).
- **0.1.20:** The system assist session is the only way a call starts. The on-screen hang-up button is gone in every theme; end the call from the assistant's own overlay.
- **0.1.19:** Experimental call-launch methods. The orb heads home as soon as the call notification appears.
- **0.1.18:** The ODST face is the same size as the other orbs. Idle beats run every 4 to 6 seconds in all themes, mixing micro-animations with full activities.
- **0.1.17:** ODST theme. The app-nudge trail and highlight box over the ring are gone in all themes.
- **0.1.16:** Optional remote updates from a paired PC. Release APKs are arm64-only (`-Pemu` adds x86_64), and the Vosk model is stored compressed.
- **0.1.15:** Orb companion: per-theme personalities, activities, chat bubbles, poke, pet, and tilt.

## Permissions

Beacon asks for each permission only when the feature that needs it is turned on:

- **Usage access:** shows most-used apps.
- **Notification access:** powers the bubbles.
- **Microphone:** used for the wake word only.
- **Coarse location:** used for weather. Without it, weather can use the fixed default location setting; with "Where I am" selected it shows "Location off" instead.
- **Display over other apps:** optional.
- **Install unknown apps:** only for the optional remote updater. You turn it on yourself, and every install still goes through Android's prompt.
- **Accessibility services:** used for the optional hang-up helper and bridge control. These are off until you enable them.

## License

[GPL-3.0](LICENSE). The keyboard module (`emitkb/`) is ported from the GPLv3 Emit project, so the whole app uses the same license.

### Third-party assets and attribution

- **Vosk small US English model** (`app/src/main/assets/model/`, vosk-model-small-en-us-0.15, unmodified): Copyright 2020 Alpha Cephei Inc., Apache License 2.0. See `app/src/main/assets/licenses/Vosk-model-Apache-2.0.txt`.
- **Dictionary data** (`app/src/main/assets/dict/words.bin`): adapted from [wordfreq](https://github.com/rspeer/wordfreq) by Robyn Speer (truncated and converted to TSV), licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Full attribution and upstream source credits are in `app/src/main/assets/DICTIONARY_LICENSE.txt`. `dict/bigrams.bin` is computed from public-domain Project Gutenberg texts.
- **Emoji data** (`app/src/main/assets/emoji.tsv`): generated from Unicode `emoji-test.txt` (Emoji 14.0), Copyright © Unicode, Inc., Unicode License v3. See `app/src/main/assets/licenses/Unicode-License-v3.txt`.
- **JetBrains Mono and Oxanium fonts**: SIL Open Font License 1.1. See `app/src/main/assets/licenses/`.

"Grok" and other app names mentioned in the code belong to their respective owners. No third-party logos are included. The ODST theme is an original fan-inspired skin; its art, chime, and text are original, and no game assets, audio, or logos are included.
