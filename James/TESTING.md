# Testing Azarah

Two kinds of tests:

1. **Unit tests**: run on your laptop in seconds, no phone needed. They cover what Azarah does with the words you say.
2. **The car checklist** below: the things only a real phone, real Bluetooth and a real microphone can show.

## 1. Unit tests

```
gradlew test
```

(`./gradlew test` on Mac/Linux.) The report is at `app/build/reports/tests/testDebugUnitTest/index.html`.

| File | What it checks |
| --- | --- |
| `app/src/test/.../CommandsTest.kt` | Every voice command: music, playback, map, "take me to…", saved places, Go / Cancel during the countdown |
| `app/src/test/.../MatchingTest.kt` | Playlist names heard slightly wrong ("afro beats" → Afrobeats), saved places ("work" → Office), cleaning up spoken addresses |
| `app/src/test/.../LifecycleTest.kt` | Delayed music requests, controller Pause, Bluetooth permission revocation, parking cancellation/date labels, cancelled trips, stale recognizer callbacks, microphone ownership and bubble accessibility clicks, using Robolectric on Android API 35 |

The command tests exercise `Commands.kt`; lifecycle tests exercise the service, overlay, speech and parking components. When you change these behaviors, add a regression case. Robolectric cannot validate real Bluetooth routing or native microphone capture; use the checklist below for those.

## 2. Car checklist

Do this after any change to Bluetooth, music, voice, the overlay or parking. Tick each line; if one fails, run `adb logcat -s James` while repeating it and keep the output.

Phone: ____________  Android version: ____  Azarah version (setup screen → bottom of README): ____  Date: ________

### Start and stop

- [ ] Car connects with the phone **unlocked**: the sheet slides up and music starts from the car speakers (not the phone) within about 5 s
- [ ] Car connects with the phone **locked**: music starts; after unlocking, the bubble is there
- [ ] Turn the car off: music pauses at once; about 10 s later the notification and bubble disappear
- [ ] Bluetooth off and on again within 10 s: music carries on, nothing restarts, **no** parking notification
- [ ] Next drive: music continues from the same song and second

### Music

- [ ] Steering-wheel (or earbud) next / previous / pause work
- [ ] Press Pause while a playlist is loading, including when already paused: the delayed playlist does not start
- [ ] Disconnect while a playlist is loading: no music starts from the phone speaker
- [ ] MUSIC shows **every** folder in `Music`, including ones just copied from a laptop; empty folders say "No songs"
- [ ] Tap a playlist: it plays, Azarah says "Playing …", the sheet shrinks to the bubble
- [ ] Shuffle all works
- [ ] A phone call pauses the music; it comes back after the call

### Voice ("Hey Azarah")

Keep `adb logcat -s James` running if you can: each "Wake listener heard: …" line shows what was heard and the confidence of each word.

- [ ] Setup → Wake word says Ready; the bubble's dot is green while driving
- [ ] Azarah speaks in a female voice. If not, check the log for "Voice: …" or "No female voice found", and install Google's "Speech Services" voices (Settings → Text-to-speech)
- [ ] The app icon, bubble and notification show the new "A"; the app is called Azarah under its icon
- [ ] "Hey Azarah" with music playing at normal volume wakes Azarah, without touching the phone first. Try "Hello Azarah" and "Okay Azarah" too
- [ ] Say "Hey Azarah", wait for the listening prompt, then "next" / "pause" / "play" / "music" / "shuffle": each does the right thing
- [ ] In one breath: "Azarah, play the music", "Azarah, next song", "Azarah, pause", "Azarah, take me home": each happens straight away
- [ ] "Azarah, take me to …" opens the map and asks "Where are we going?"
- [ ] After "Hey Azarah", mumble or stay silent: Azarah says "Sorry, I didn't catch that. Say it again." and listens again, without the wake word; after two misses she stops
- [ ] "Hey Azarah", then "play gospel" (use one of your playlist names) plays it
- [ ] "Hey Azarah", then "take me to Computer Village" goes straight to the Go countdown
- [ ] "Hey Azarah" during the countdown, then "cancel": the trip is cancelled. Then again, but say something random: the countdown carries on
- [ ] Tap Cancel while Azarah is listening for Go/Cancel: a late "Go" cannot launch navigation
- [ ] Azarah doesn't wake up by herself over 10 minutes of music and talking (including saying "Sarah", "the Sahara", "area")

### Maps

- [ ] An address you say opens Google Maps in driving navigation after the 3-2-1 countdown
- [ ] "Take me to *saved place*" uses the saved address
- [ ] The bubble stays on top of Google Maps; tapping it opens the sheet over the map

### Parking

- [ ] With "Remember where I park" on, turning the car off shows "Your car is parked here" within about 10 s
- [ ] Tapping it opens walking directions to the car
- [ ] Setup → Where did I park? shows the time and Find my car
- [ ] Next time the car connects, the parking notification disappears
- [ ] Disable parking or clear the pin during capture: no late pin or notification appears

### Setup and permissions

- [ ] A fresh install: every setup row can be ticked green from the setup screen alone
- [ ] Turning a permission off in Android settings shows the row un-ticked again, and Azarah doesn't crash
