# ZARA AI Mobile Assistant (Android APK source)

A lightweight Android app with a dark mobile UI, voice input, Android text-to-speech, local notes, app-launch shortcuts, music search, and optional connection to an existing ZARA `/api/chat` server.

## What works on the phone
- Save, read aloud, search, edit and delete notes. Notes are stored locally in app SharedPreferences.
- Voice input through Android's installed speech-recognition service. Telugu is requested first; actual offline recognition depends on the phone's language packs/services.
- Spoken replies through Android TextToSpeech. Telugu and a female voice are selected only if supported/installed by the device; otherwise Android's available fallback voice is used.
- Open common installed apps (WhatsApp, YouTube, YouTube Music, Spotify, Chrome, Maps, Phone, Settings).
- Open music search in YouTube Music; uses a web fallback if needed. It does not bypass subscriptions or DRM.
- Optional AI: set the base URL of a reachable ZARA backend that implements `POST /api/chat` and returns `{ "reply": "..." }`. Provider should be `ollama` or `gemini` as accepted by the existing ZARA server.
- Offline command routing and notes work without an AI server. Full general-purpose offline chat is not included in this starter app.

## Build the APK without Android Studio (GitHub Actions)
1. Create a GitHub repository and upload the contents of this folder (not the parent folder) to its root.
2. Open the repository's **Actions** tab and enable workflows if prompted.
3. Select **Build ZARA AI APK** → **Run workflow**.
4. When the run completes, open it and download the artifact named `ZARA-AI-debug-APK`.
5. Extract the artifact ZIP on your phone or PC, transfer `app-debug.apk` to Android, and install it. If Android asks, allow installing apps from that source.

The workflow uses Java 17 and Gradle 8.9; it downloads the Android Gradle Plugin and Android SDK packages on GitHub's runner.

## Build locally (optional)
Requires JDK 17, Android SDK platform 35 and Gradle 8.9. From the project root run `gradle assembleDebug`; APK output is `app/build/outputs/apk/debug/app-debug.apk`.

## Connect to the existing ZARA server
- `http://localhost:3000` on the phone means the phone itself, not your Windows PC.
- On the same Wi-Fi, a PC LAN address may work if the PC firewall allows it.
- For mobile data from anywhere, deploy the ZARA backend to a secure public HTTPS host and enter that URL in the app.
- Do not publish a backend with no authentication or expose API keys in client code. The existing simple ZARA server may need authentication and HTTPS hosting before public deployment.

## Known limitations
- This is a starter implementation. The current UI does not implement a wake word or always-listening background service.
- App-launch support depends on which apps are installed and Android restrictions.
- TTS voice gender/language availability varies by device and installed voice packs.
- The app asks Android's speech-recognition service to recognize Telugu. Many Android speech-recognition providers process speech online unless an offline language pack is installed.
- Android may show a microphone permission prompt the first time voice input is used.
