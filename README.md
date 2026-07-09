# TalkBridge 🇬🇧↔🇵🇹

An offline English ↔ Portuguese voice translator for Android. Tap a button, speak, and the phone speaks the translation aloud. After first-run setup, **no internet and no AI tokens are ever needed**.

- Speech recognition: [Vosk](https://alphacephei.com/vosk/) (on-device, models bundled into the APK by the build)
- Translation: Google ML Kit on-device translation (model packs download once on first launch, then work offline forever)
- Voice output: Android's built-in text-to-speech

---

## Building it — entirely from a phone

You never compile anything yourself. GitHub's servers build the APK for you.

### 1. One-time setup

1. Create a free account at https://github.com if you don't have one.
2. Install **Termux** from **F-Droid** (https://f-droid.org — the Play Store version is outdated). 
3. In Termux:
   ```bash
   pkg update
   pkg install git gh unzip
   termux-setup-storage    # grants access to your Downloads folder
   ```

### 2. Get this project into a GitHub repo

Assuming you downloaded `TalkBridge.zip` to your phone's Downloads:

```bash
cd ~
cp ~/storage/downloads/TalkBridge.zip .
unzip TalkBridge.zip
cd TalkBridge

gh auth login        # choose GitHub.com → HTTPS → login via browser, enter the code shown

git init
git add .
git commit -m "Initial commit"
git branch -M main
gh repo create talkbridge --private --source=. --push
```

### 3. Download the APK

1. In your phone browser, go to your repo: `https://github.com/YOURNAME/talkbridge`
2. Tap the **Actions** tab. A "Build APK" run starts automatically after the push. Wait ~5 minutes for the green tick.
3. Open the completed run, scroll to **Artifacts**, download **TalkBridge-apk**.
4. Unzip it (your Files app can, or `unzip` in Termux) to get `app-debug.apk`.
5. Tap the APK to install. Android will ask you to allow installs from your browser/Files app — allow it.

### 4. First launch (needs internet once)

- Grant microphone permission when asked.
- The app downloads the ML Kit English↔Portuguese translation packs (~60 MB total) on first launch. Wait for the status line to say **Ready**.
- After that, the app is fully offline. Test it in airplane mode.

### 5. Portuguese voice (recommended)

For spoken Portuguese output, make sure an offline Portuguese TTS voice is installed:
**Settings → General management (or System) → Text-to-speech → Google Speech Services → Install voice data → Português (Portugal)**.
Menu names vary slightly by manufacturer.

---

## Using it

- 🇬🇧 person taps **I speak English**, talks, taps **Stop**. The phone shows and speaks the Portuguese.
- 🇵🇹 person taps **Eu falo Português**, fala, toca em **Parar**. O telemóvel mostra e diz a versão em inglês.
- The conversation history stays on screen so anyone can re-read it.

## Making changes

Edit the code (in Termux with `nano`/`vim`, or any editor app), then:

```bash
git add .
git commit -m "describe change"
git push
```

Every push triggers a fresh APK build in the Actions tab.

## Notes & known limits

- Vosk "small" models are used to keep the APK reasonable (~120 MB). Recognition is good for clear conversational speech; heavy accents or noisy rooms will degrade it.
- The Portuguese Vosk model is trained mostly on European + Brazilian Portuguese mixed; it handles pt-PT well.
- ML Kit translation is solid for everyday conversation, weaker on idioms — keep sentences simple for best results.
- This is a debug-signed APK for personal use. It can't go on the Play Store as-is (that would need a signing key, easily added later).
