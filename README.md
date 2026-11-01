# Vigil

**A JARVIS-style voice AI assistant for Android that replaces Google Assistant.**

Long-press the power button, speak, and Vigil understands you, acts on your phone, and answers out loud. It is built natively in Kotlin and registers as Android's default digital assistant, so it works system-wide, including from the lock screen.

<!-- Screenshots: add images to docs/screenshots/ and uncomment
<p align="center">
  <img src="docs/screenshots/assistant.png" width="250" />
  <img src="docs/screenshots/setup.png" width="250" />
  <img src="docs/screenshots/history.png" width="250" />
</p>
-->

---

## Features

### Assistant
- Replaces Google Assistant / Gemini as the **default digital assistant**
- Opens with a **power-button long-press** (or the assist gesture), including on the lock screen
- **Follow-up conversation**: the mic reopens after each reply; say "stop" or stay silent to close
- **Multi-step commands**: "Set an alarm for 6 and turn on the torch" runs both

### Voice
- **Speech-to-text** with Groq Whisper (`whisper-large-v3-turbo`), tuned for Indian accents
- Contact names and a **custom vocabulary** are sent as spelling hints (e.g. local place names)
- Built-in voice-activity detection: it starts and stops recording automatically
- **Text-to-speech** replies using Android's TTS engine

### Phone actions
| Category | What you can say |
|---|---|
| Alarms & timers | "Wake me at 6:30", "Set a timer for 10 minutes" |
| Apps | "Open YouTube" |
| Flashlight | "Turn on the torch" |
| Calls & SMS | "Call Arjun", "Text Mom I'll be late" |
| Sound | "Volume to 30", "Put my phone on vibrate", "Turn on Do Not Disturb" |
| Display | "Brightness 20 percent" |
| Calendar | "What's on my calendar tomorrow?", "Add lunch with Rahul at 1 PM tomorrow" |

### Information
- **Weather** for your location or any city (Open-Meteo)
- **Web search** for news, scores and anything recent (Tavily)

### Memory
- **Long-term memory**: "Remember my bike service is on the 5th", then later "When is my bike service?"
- **Command history**: the setup screen shows recent commands, the tools used and Vigil's replies

---

## How it works

```
Power button long-press
        │
        ▼
VoiceInteractionService ──► AssistActivity (bottom-sheet overlay)
                                   │
                                   ├─ AudioRecord 16 kHz + voice-activity detection
                                   ├─ Groq Whisper  ─────────────► text
                                   │
                                   ├─ Groq LLM (gpt-oss) with tool calling
                                   │     ├─ system prompt + long-term memory
                                   │     └─ recent conversation history
                                   │
                                   ├─ Tools.kt runs the chosen tools on the phone
                                   │     (intents, AudioManager, CalendarContract,
                                   │      Open-Meteo, Tavily, …)
                                   │
                                   └─ TextToSpeech ──► spoken reply ──► listen again
```

The LLM never controls the phone directly. It only chooses a tool and its arguments; the app's Kotlin code runs it and sends the result back for the final reply.

---

## Tech stack

| Layer | Technology |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (Material 3) |
| Assistant integration | `VoiceInteractionService`, `VoiceInteractionSession` |
| Speech-to-text | Groq Whisper `whisper-large-v3-turbo` |
| LLM | Groq `openai/gpt-oss-20b` (with automatic fallback to `gpt-oss-120b`) |
| Text-to-speech | Android `TextToSpeech` |
| Weather | Open-Meteo (forecast + geocoding) |
| Web search | Tavily Search API |
| Storage | `SharedPreferences` (memory, vocabulary, history) |
| Networking | `HttpURLConnection` + `org.json` (no extra libraries) |

---

## Requirements

- Android **10+** (API 29); developed and tested on a Pixel 9 running Android 17
- Android Studio
- A **Groq** API key: [console.groq.com](https://console.groq.com) (free tier available)
- A **Tavily** API key: [app.tavily.com](https://app.tavily.com) (free tier available)

---

## Setup

1. **Clone the repo** and open it in Android Studio.

2. **Add your keys** to `local.properties` in the project root (this file is git-ignored):
   ```properties
   LLM_BASE_URL=https://api.groq.com/openai/v1
   LLM_API_KEY=your_groq_key
   LLM_MODEL=openai/gpt-oss-20b
   TAVILY_API_KEY=your_tavily_key
   ```

3. **Sync Gradle**, connect your phone with USB debugging on, and press **Run**.

4. In the Vigil app, complete the setup screen:
    1. Allow permissions (mic, contacts, phone, SMS, calendar, location)
    2. Set Vigil as the **Digital assistant app**
    3. Allow **Do Not Disturb access** (for DND and silent mode)
    4. Allow **Modify system settings** (for brightness)

5. On Pixel phones, set *Settings → System → Gestures → Press and hold power button* to **Digital assistant**.

6. Long-press the power button and start talking.

---

## Permissions

| Permission | Used for |
|---|---|
| `RECORD_AUDIO` | Listening to your voice |
| `INTERNET` | Whisper, LLM, weather and search |
| `READ_CONTACTS` | Finding who to call or text; contact names as spelling hints |
| `CALL_PHONE`, `SEND_SMS` | Calls and text messages |
| `READ_CALENDAR`, `WRITE_CALENDAR` | Reading and adding calendar events |
| `ACCESS_COARSE_LOCATION` | Local weather (approximate location only) |
| `SET_ALARM` | Alarms and timers |
| `ACCESS_NOTIFICATION_POLICY` | Do Not Disturb and silent mode |
| `WRITE_SETTINGS` | Screen brightness |

---

## Privacy

- API keys live only in `local.properties` and are never committed.
- Long-term memory, custom vocabulary and command history are stored **only on the device**, in app-private storage.
- To answer, Vigil sends your recorded speech to Groq (Whisper), your request plus saved facts to Groq (LLM), search queries to Tavily, and approximate coordinates to Open-Meteo.
- Don't ask Vigil to remember passwords or PINs.

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── res/xml/interaction_service.xml   # registers Vigil as an assistant
└── java/com/rishi/vigil/
    ├── Services.kt          # VoiceInteractionService + session + recognizer stub
    ├── AssistActivity.kt    # overlay UI, recording, Whisper, LLM loop, TTS
    ├── Tools.kt             # tool definitions and their Android implementations
    ├── Memory.kt            # long-term facts, custom words, command history
    └── MainActivity.kt      # setup screen, vocabulary, memory and history viewer
```

---

## Limitations

- Cannot respond to "Hey Google"; that hotword is reserved for Google.
- Android only; iOS does not allow replacing Siri.
- Free API tiers have rate limits; heavy back-to-back use may briefly hit them.
- When installed from an APK file, Android may require *Allow restricted settings* for some permissions.

---

## Roadmap

- [ ] "Hey Vigil" wake word
- [ ] Screen awareness ("summarise this page")
- [ ] Reading notifications
- [ ] WhatsApp messages
- [ ] Streaming replies for lower latency

---

## Credits

- Weather data by [Open-Meteo.com](https://open-meteo.com) (CC BY 4.0)
- Speech-to-text and LLM inference by [Groq](https://groq.com)
- Web search by [Tavily](https://tavily.com)

---

## License

MIT. See [LICENSE](LICENSE).

---

Built by **Rishi Bagodi** · B.Tech Information Science Engineering, JAIN (Deemed-to-be University), Bengaluru