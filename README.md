# Rokid Translator 🇪🇸🇮🇹↔🇺🇸

Real-time translation app for Rokid AR glasses — built for a Spain/Italy trip.

Speak into the glasses mic, see translations displayed on the glasses in real-time.

## Features

- **Language pairs:** Spanish ↔ English, Italian ↔ English
- **Auto language detection** — speak in any supported language, it figures out the direction
- **Dual translation pipeline:**
  - 📱 **ML Kit** (on-device, instant) — shows result immediately
  - ☁️ **Gemini** (cloud, higher quality) — updates result after
- **Glasses display:** Large, clean text with detected language + translation
- **Phone UI:** Minimal — connection status, language selector, translation feed, settings

## Architecture

```
┌──────────────┐  Bluetooth SPP  ┌──────────────┐
│  glasses-app │ ◄──────────────► │  phone-app   │
│  (Rokid AR)  │                  │  (Android)   │
│              │                  │              │
│ • Record mic │  audio data ──► │ • STT        │
│ • Display    │                  │ • ML Kit     │
│   translation│ ◄── results ──  │ • Gemini     │
└──────────────┘                  └──────────────┘
```

### Modules

| Module | Package | Description |
|--------|---------|-------------|
| `common` | `com.rokid.translator.common` | Shared protocol, constants, message types |
| `phone-app` | `com.rokid.translator` | Phone app — BT server, STT, translation engines |
| `glasses-app` | `com.rokid.translator.glasses` | Glasses app — mic recording, display, BT client |

## Setup

### Prerequisites

- Android Studio (Ladybug or newer)
- Android SDK 36
- A Gemini API key ([Get one here](https://aistudio.google.com/apikey))

### Configuration

1. Copy `local.properties.template` to `local.properties`
2. Add your Gemini API key:
   ```
   GEMINI_API_KEY=your_key_here
   ```
3. (Or configure it in the app's Settings panel at runtime)

### Building

```bash
# Phone app (install on your Android phone)
./gradlew :phone-app:assembleDebug

# Glasses app (install on Rokid glasses)
./gradlew :glasses-app:assembleDebug
```

### Installing

```bash
# Phone
adb -s <phone-serial> install phone-app/build/outputs/apk/debug/phone-app-debug.apk

# Glasses
adb -s <glasses-serial> install glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

## Usage

1. **Pair** your phone and Rokid glasses via Bluetooth (system settings)
2. **Launch** "Rokid Translator" on both devices
3. **Phone:** The app auto-starts the translation service and waits for glasses connection
4. **Glasses:** Tap the screen to select your paired phone → connection established
5. **Speak:** Tap the glasses touchpad (or screen) to start recording
6. **Tap again** to stop — audio is sent to phone for processing
7. **Translation appears** on the glasses display:
   - ML Kit result shows first (instant)
   - Gemini result updates after (higher quality)

### Phone Controls

- **Language selector:** Toggle between ES↔EN and IT↔EN
- **Settings (⚙️):** Configure Gemini API key, enable/disable cloud translation
- **Translation feed:** Scrollable history of all translations

### Glasses Controls

- **Tap touchpad / screen:** Toggle recording
- **DPAD Center / Enter key:** Toggle recording

## ML Kit Model Downloads

On first launch, ML Kit downloads translation models (~30MB each). This requires an internet connection. Models are cached for offline use after that.

## Based On

Adapted from [RokidAIAssistant](https://github.com/zero2005x/RokidAIAssistant) — stripped down from a full AI assistant to a focused translation app. Kept the CXR SDK integration, Bluetooth SPP protocol, and core phone↔glasses architecture.

## License

MIT
