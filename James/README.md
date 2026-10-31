# Azarah: in-car Bluetooth assistant

Azarah is an Android app that wakes up when your phone connects to your car's Bluetooth. It plays your music, starts Google Maps navigation and answers to **"Hey Azarah"** (Azarah is Hebrew for "help"), so you don't have to touch the phone while driving.

- Works on any Android phone running **Android 10 or newer**.
- Everything shows on the **phone**, as a floating bubble and a sheet drawn over other apps (Google Maps included). It is not an Android Auto app; the car's Bluetooth is only the trigger.
- No account or sign-up. The "Hey Azarah" listener runs offline on the phone.

Version: **0.7.1** (see `versionName` in `app/build.gradle.kts`).

## License

This project is licensed under the [GNU General Public License v3.0 (GPL-3.0)](../LICENSE).

The Azarah name and logo are not covered by the GPL-3.0 license and may not be used in copies of this project.

## What it does

**When the car connects**
- Azarah starts on her own (an "Azarah is active" notification shows she's running).
- The sheet slides up with a now-playing card and big **MUSIC** and **MAP** tiles.
- Music starts from `Music/Azarah_Default` (or an older `Music/James_Default`) once the car's music channel is ready, continuing from the song and second where it last stopped.

**Music**
- Every folder directly inside `Music` is a playlist (songs in its subfolders count too). Tap one, or say its name.
- Previous / Play-Pause / Next on the sheet, in the notification, and on the steering wheel or earbuds.
- **Shuffle all** plays every song shuffled.

**Maps**
- Tap **MAP** (or say "map"): Azarah asks "Where are we going?" and listens.
- Say a place ("Computer Village Ikeja") or a saved place ("church"). Azarah shows **Heard: …**, counts down 3-2-1 and opens Google Maps in driving navigation. Tap **Go** to leave now or **Cancel**.
- **Quick picks** show your saved places and last 3 trips.

**"Hey Azarah"** (also "Hello Azarah", "Hi Azarah", "Okay Azarah"): say it any time. The bubble's dot is green while she's listening. Azarah answers and listens for the command; the music pauses meanwhile. If she doesn't catch it, she asks again (twice) without needing the wake word.

**In one breath**: "Azarah, play the music", "Azarah, next song", "Azarah, pause", "Azarah, take me home", "Azarah, map" are done straight away, with no question first. "Azarah, take me to …" opens the map and asks where (the wake-word listener can't hear addresses).

Say "a-ZAH-rah". On its own, a stray "Azarah" (or "Sarah") in conversation doesn't wake Azarah; it needs a greeting before it or a command after it.

After the wake word:

| Say | Azarah does |
| --- | --- |
| "Music" | Opens your playlists |
| "Play Gospel" (or just "Gospel") | Plays that playlist, even if the name is heard a bit wrong |
| "Play" / "Pause" / "Next" / "Previous" | Controls the music |
| "Shuffle" | Shuffles all your songs |
| "Map" | Asks "Where are we going?" |
| "Take me to Computer Village" | Goes straight to the Go countdown |
| "Take me home" / "Church" | Uses your saved places |
| "Go" / "Cancel" (during the countdown) | Leaves now, or cancels the trip |
| "Close" / "Never mind" | Back to the bubble |

**Saved places**: setup → **Places** → **+ Add a place**, with any name and address (up to 12). Tap a place to edit it, or **−** to remove it.

**Where did I park?** When the car disconnects, Azarah saves where you are and shows "Your car is parked here · tap for walking directions". Setup also has **Find my car**. In underground car parks the pin may be at the entrance.

**The bubble**: drag it anywhere (it snaps to the side). Tap it, or the notification, to open the sheet. Tap outside the sheet, swipe it down, or leave it for 20 s and it shrinks back to the bubble.

## First-time setup on the phone

Do this once, parked, with the car (or Bluetooth earbuds, for a desk test) paired in the phone's Bluetooth settings.

1. Open **Azarah**.
2. **Permissions**: allow Bluetooth, notifications, music and microphone.
3. **Your car**: pick your car's Bluetooth from the list. To change car later, tap it again.
4. **Run in background**: set Azarah to *Unrestricted* / *Don't optimise*. On Xiaomi, Tecno, Infinix, itel, Oppo and Samsung, also turn on **Autostart** for Azarah in the phone's settings.
5. **Show over other apps**: switch it on for Azarah.
6. **Wake word → Voice model**: tap to download it (about 40 MB, once; Wi-Fi recommended).
7. Optional: add **Places**, and tap **Remember where I park** (needs location set to **"Allow all the time"**).

Every row ticks green when it's done.

### Music folders

```
Internal storage/
└── Music/
    ├── Azarah_Default/  ← plays automatically when the car connects
    ├── Gospel/          ← a playlist
    └── Afrobeats/       ← another playlist
```

MP3, M4A, AAC, OGG, OPUS, FLAC and WAV all work. Folder names aren't case-sensitive; underscores are read as spaces ("Azarah_Default" is "Azarah Default").

## Building

You need **JDK 17 or newer** and the **Android SDK** (Android Studio, or the command-line tools with `platform-tools`, `platforms;android-35` and `build-tools;35.0.0`). Set `ANDROID_HOME` to the SDK folder.

With the phone plugged in and USB debugging on:

```
gradlew installDebug        # Windows
./gradlew installDebug      # Mac / Linux
```

The first build downloads Gradle and the libraries, so it needs internet and takes a few minutes.

## Testing

```
gradlew test
```

runs the unit tests for voice commands, playlist matching and saved places, plus Android lifecycle regressions under Robolectric, with no phone needed. The first test run downloads its Android runtime. Before a release, go through the car checklist in **[TESTING.md](TESTING.md)**.

## Troubleshooting

Logs: plug the phone in and run `adb logcat -s James`.

| Problem | Try |
| --- | --- |
| Nothing happens when the car connects | Setup → check every row is green. Re-pick the car in **Your car**. Turn on Autostart (Xiaomi, Tecno, Infinix, itel, Oppo, Samsung). |
| Music plays from the phone, not the car | It shouldn't any more (Azarah waits for the car's music channel). If it does, send the log. |
| A playlist folder is missing | It must be directly inside `Music`. Empty folders show as "No songs". |
| "Hey Azarah" doesn't answer | Say "Hey Azarah" or "Azarah, play the music" clearly (a-ZAH-rah). Setup → Wake word must say Ready, and the bubble's dot must be green. If the phone was locked when the car connected, unlock it once. Mount the phone facing you. |
| Azarah wakes up by herself | Run `adb logcat -s James` and look for "Wake listener heard: …" lines: they show what was heard and each word's confidence. Raise `WakePhrase.MIN_CONFIDENCE` (0.6) or add the misheard word to its decoy list. |
| No parking notification | Setup → Where did I park? must say On; location must be **Allow all the time**. |

## How it's built

| Part | Uses |
| --- | --- |
| Car trigger | `CompanionDeviceManager` (Android 12+) and a Bluetooth broadcast receiver (all versions) |
| Background | One foreground service, `JamesService` |
| Music | Media3 ExoPlayer + MediaSession (steering-wheel buttons) |
| Overlay | `WindowManager` overlay windows drawn with plain Android views |
| "Hey Azarah" | Vosk (offline speech recognition, Apache 2.0), limited to a short phrase list in `WakePhrase.kt` |
| Commands and addresses | Android `SpeechRecognizer`, then `Commands.kt` |
| Azarah's voice | Android `TextToSpeech` |
| Navigation | Google Maps `google.navigation:` intent, with fallbacks |
| Parking | Android `LocationManager` |

### Source files (`app/src/main/java/com/james/assistant/`)

| File | What it does |
| --- | --- |
| `JamesService.kt` | The service that runs while the car is connected: music, overlay, wake word, navigation, notification |
| `SetupActivity.kt` | The setup screen |
| `overlay/OverlayController.kt` | The bubble and the sheet (Home, Music and Map pages, voice flows) |
| `overlay/Views.kt` | Waveform, progress bar and sheet gestures |
| `Commands.kt` | What each spoken command means; playlist and place matching (unit-tested) |
| `WakeWord.kt` / `WakePhrase.kt` / `VoiceModel.kt` | "Hey Azarah" listener, what counts as the wake word, and the model download |
| `VoiceInput.kt` / `Speaker.kt` | Speech-to-text for commands; Azarah's spoken replies |
| `MusicLibrary.kt` | Finds playlists and songs |
| `Places.kt` / `Place.kt` | Saved places |
| `Parking.kt` | Where did I park? |
| `CarConnection.kt`, `CarPresenceService.kt`, `BluetoothCarReceiver.kt` | Car connect / disconnect |
| `Prefs.kt`, `Ui.kt` | Saved settings; colours and shared UI pieces |

## Changelog

- **0.8** (not yet built): The app is now called **Azarah**, with an "A" icon and bubble and a female voice where the phone has one; the default music folder is `Azarah_Default` (an existing `Azarah_Default` still works). Wake word is "Azarah" ("Hey / Hello / Hi / Okay Azarah"), and a command can follow in one breath ("Azarah, play the music"). Fewer false wakes: only finished utterances count, each word needs good confidence, and sound-alike decoy words absorb near-misses. "Sorry, I didn't catch that" listens again instead of going back to sleep. Fixes the Go countdown freezing on misheard words, duplicate place names on rename, and a lost speech callback during start-up.
- **0.7.1**: Cancels stale music and voice requests across disconnect, Pause and trip cancellation; handles repeated controller Pause and buffering; preserves destination/playlist names and matches punctuated saved places; fixes parking cancellation and calendar labels; guards revoked Bluetooth permission; defers wake reload during commands and cleans up native listeners; supports accessibility clicks on the bubble. Adds Android lifecycle regression tests.
- **0.7**: Unit tests and the car checklist; voice-command logic moved into `Commands.kt`; short words no longer match playlists by accident.
- **0.6.2**: Autoplay waits for the car's music channel; wake word no longer restarts during a command; music library read in the background; no stale parking notifications; parking notification works without Google Maps.
- **0.6.1**: "Hey Azarah" gets the microphone as soon as Azarah is on screen.
- **0.6**: Saved places (add, edit, remove, any name); Where did I park?
- **0.5**: "Hey Azarah" with Vosk and voice commands; all Music folders shown, including unindexed songs.
- **0.4**: Maps by voice with Go countdown and quick picks.
- **0.3**: Bubble and sheet overlay, now-playing card, playlist list, spoken feedback.
- **0.2**: Music from `Azarah_Default` on connect, resume where it stopped, car and earbud buttons.
- **0.1**: Starts and stops with the car's Bluetooth.

_The Azarah name and logo are not covered by the GPL and may not be used for modified versions._