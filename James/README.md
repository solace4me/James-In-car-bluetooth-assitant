# JAMES — Milestone 5: "Hey James"

Works on any Android phone running Android 10 or newer.

What James does now:
- **Milestone 1:** starts when your saved car/Bluetooth device connects, stops 10 s after it disconnects.
- **Milestone 2:** on connect, plays every song in `Music/James_Default`, looping.
  - Previous / Play-Pause / Next / Stop buttons in the "James is active" notification.
  - The car's steering-wheel buttons and earbud buttons control it too.
  - If the car disconnects, music pauses at once. Next time it connects, music continues from the same song and second (or starts James_Default if that song is gone).

- **Milestone 3:** the overlay, drawn on top of any app (Google Maps included).
  - On connect, the sheet opens: James's status, a **now-playing card** (album art, song, folder, progress, Previous / Play-Pause / Next), and the **MUSIC** and **MAP** tiles. Tap outside it, swipe it down, or leave it 20 s, and it shrinks to the "J" bubble.
  - Drag the bubble anywhere; it snaps to the nearest side. Tap it to open the sheet again. Tapping the notification also opens it.
  - **MUSIC** shows a card per folder (album art from its first song), with the playing folder outlined in green. Tap a card to play it; James says "Playing Gospel". **Shuffle all** plays every song shuffled.
  - **MUSIC** list scrolls when you have many playlists (scrollbar and faded edges show there's more).
  - If the bubble ever goes missing, James puts it back within 2 seconds.

- **Milestone 4:** Maps by voice.
  - Tap **MAP**: James asks "Where are we going?", then listens (music pauses while he listens).
  - Say a place, e.g. "Computer Village Ikeja", or "take me to Lekki Phase 1". The card shows **Heard: …**, James says "Going to …", and the **Go** button counts down 3-2-1, then Google Maps opens in driving navigation. Tap **Go** to leave straight away, or **Cancel**.
  - Say **"home"** or **"office"** to use the addresses saved in setup. **Quick picks** show Home, Office and your last 3 trips.
  - Didn't catch it? James asks again (twice), then offers **Speak again**.

- **Milestone 5:** the wake word and voice commands.
  - Say **"Hey James"** (or "Okay James") any time. The sheet opens, James says "Speak on, I'm listening", and the music pauses while he listens. The bubble's dot is **green** while he's listening for the wake word.
  - Then say:

    | Say | James does |
    | --- | --- |
    | "Music" | Opens your playlists |
    | "Play Gospel" (or just "Gospel") | Plays that folder, even if you say it a bit differently |
    | "Play" / "Pause" / "Next" / "Previous" | Controls the music |
    | "Shuffle" | Shuffles all your songs |
    | "Map" | Asks "Where are we going?" |
    | "Take me to Computer Village" | Goes straight to the Go countdown |
    | "Take me home" / "Go to the office" | Uses your saved places |
    | "Go" / "Cancel" (during the countdown) | Leaves now, or cancels the trip |
    | "Close" / "Never mind" | Back to the bubble |

## Wake word setup (once)

"Hey James" uses **Vosk**, a free, open-source speech engine that runs on the phone without the internet. No account, key or training needed.

1. Open James setup → **Wake word** → **Voice model** and tap **Download** (about 40 MB, once; Wi-Fi recommended).
2. When it says **Ready**, James listens for "Hey James" from the next car connection (or straight away if he's already running).

James only listens for "hey james" / "okay james"; anything else is ignored, which keeps false wake-ups from music and chatter low.

## Music folders

Put songs on the phone like this (folder names are not case-sensitive):

```
Internal storage/
└── Music/
    ├── James_Default/   ← plays automatically on connect
    ├── Gospel/          ← playlists for Milestone 3
    └── Afrobeats/
```

MP3, M4A, AAC, OGG, FLAC and WAV all work. If you just copied songs from a laptop and James says the folder is empty, wait a minute or restart the phone so Android indexes the new files.

## Build and install

1. Unzip over your existing James folder (replace files).
2. Phone plugged in, USB debugging on: `gradlew installDebug` (Windows) or `./gradlew installDebug` (Mac/Linux).

## Test

1. Open James and tap **Permissions**: allow the new **microphone** permission. Under **Places**, add your Home and Office addresses (optional).
2. The screen should show `Default music: N songs in James_Default` and your other folders.
3. Close James, turn your Bluetooth device off, then on. Music should start within a few seconds, and the notification shows the song with Previous / Pause / Next.
4. Press the next/pause buttons on the car or earbuds.
5. Turn the device off: music pauses right away, and James stops about 10 s later.

## Logs

`adb logcat -s James`
