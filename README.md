# TalkBridge 🇬🇧↔🇵🇹

An offline English ↔ European Portuguese voice translator for Android. Select
your language, speak, and the phone speaks the translation aloud, showing both
texts as a conversation history. After first-run setup, **no internet, no
accounts, and no AI tokens are ever needed**.

Built and maintained entirely from an Android phone using Termux, with all
compilation done by GitHub Actions.

## How it works

```
mic ──▶ Silero VAD ──▶ Whisper-small (forced to the selected language)
              │                      │
              │            EN mode: ML Kit EN→PT
              │            PT mode: translation ensemble —
              │              Whisper translate-task (audio→EN) vs ML Kit (text),
              │              agreement-gated: fluent when they agree,
              │              faithful when they diverge
              ▼                      │
        level meter            Android TTS speaks the translation
```

**Speech recognition:** OpenAI Whisper `small` (int8) running on-device via
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Two forced-language
transcribe instances plus one translate-task instance.

**Voice activity detection:** Silero neural VAD segments speech into
utterances; silence never reaches the decoder (Whisper hallucinates on
silence). Segments end after 0.8s of quiet.

**Language selection:** manual lock via two mode buttons. Earlier versions
auto-detected the language (spoken-language ID, dual-decode arbitration,
transcript-vs-translation similarity). All were retired: language ID on short
utterances has an irreducible error tail, and Whisper covertly *translates*
wrong-language audio rather than garbling it, defeating quality heuristics.
Manual lock is deterministic, and halves the decode work.

**Translation:** EN→PT via ML Kit on-device translation. PT→EN via an
ensemble — Whisper's native translate task (better fluency, works from the
audio) cross-checked against ML Kit on the transcript; Whisper's wording is
used only when the two independently agree, which filters Whisper's phonetic
passthrough failures ("cerveja" → "survey").

**Hygiene:** a transcript sanitizer collapses Whisper repetition loops,
strips event tags (`[Música]`, `(laughs`), and rejects hallucinated segments.
Echo suppression mutes the mic while the phone itself is speaking.

## Installing

Grab `TalkBridge.apk` from the newest entry on the
[Releases page](../../releases), allow installs from your browser/files app,
and install. First launch needs internet once (~60MB of ML Kit translation
packs); after that it is fully offline — works in airplane mode.

Recommended: install the offline **Português (Portugal)** voice under
Settings → Text-to-speech → Install voice data, so spoken output is pt-PT.

## Using it

1. Tap **Start conversation**
2. Tap the language of whoever is about to speak
   (🇬🇧 *I speak English* / 🇵🇹 *Eu falo Português*)
3. Talk naturally in full phrases — context-rich sentences transcribe far
   better than single words ("eu quero uma maçã" beats "maçã")
4. The 👂 icon shows it hearing you; ✍ shows transcription; 🔊 while it speaks
5. Tap the other language button when the other person replies

**Update button** (top right): checks GitHub Releases, downloads, and installs
the newest build over the top — every push to `main` publishes one.

## Development

The app is developed from a phone. Typical loop:

```bash
# in Termux, after downloading a changed file from the AI pair-programmer:
bash ~/deploy.sh <content-marker> "commit message"
# → verifies the file by content, copies it into place, pushes,
#   GitHub Actions builds & releases, then: Update button in the app
```

- CI: `.github/workflows/build.yml` downloads the sherpa-onnx AAR and all
  model files at build time (repo stays small), builds a signed release APK
  (persistent keystore via repo secrets), uploads it as an artifact, and
  publishes a Release on pushes to `main`.
- Crash reporting: any crash writes a stack trace; the next launch displays
  it and copies it to the clipboard for pasting into a debugging chat.
- Tuning knobs live as constants at the top of `MainActivity.kt`
  (VAD thresholds, sentence-gap length, ensemble agreement level).

## Hardware notes

Runs comfortably on 8GB-RAM midrange devices (developed on a CMF Phone 1,
Dimensity 7300). APK is ~300MB (Whisper-small weights baked in); model load
takes ~20s at app start; decode is roughly 2–5s per utterance depending on
SoC. Accuracy improves markedly when the phone is within arm's reach of the
speaker.

## Limits (honest ones)

- Near-homophones without context (maçã/massa) can transcribe wrongly —
  speak in phrases, not single words
- ML Kit translation is literal on idioms
- One utterance at a time; overlapping speakers confuse the VAD
- This is a personally-signed APK for private use, not a Play Store app
