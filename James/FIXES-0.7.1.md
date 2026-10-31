# James 0.7.1: fixes and verification

Reviewed and fixed on October 30, 2026. Changes are confined to this updated app directory; the older `James` checkout was preserved.

## Fixes

- Background playlist results cannot restart playback after disconnect, Pause, shutdown, or a newer playlist request. Invalidation covers the overlay lookup and service lookup, including steering-wheel/controller Pause when already paused.
- Playback controls and notification labels use requested playback state, so Pause works during buffering and its label refreshes immediately.
- Spoken destinations and playlist names preserve `James`, punctuation, numbers and Unicode names. Saved-place names use matching normalization on both sides. Short playlist inputs require exact matches, and playlist names containing `shuffle` are no longer mistaken for Shuffle All.
- Trip cancellation clears held commands and invalidates late Go results, countdown prompts and recognizer callbacks. Replaced map prompts cannot start an obsolete recognition session.
- Old speech-recognition callbacks cannot finish or destroy a newer session.
- Wake-word reload waits while command recognition owns the microphone. Paused/released listeners reject stale callbacks. Startup failure releases assigned native resources, and a model-load completion remains able to close its native model after release.
- Bluetooth connection checks and receiver callbacks handle revoked permissions.
- Clearing/disabling parking cancels pending captures; callbacks recheck enabled/permission state. Today/yesterday labels use local calendar dates.
- The bubble has a real accessibility click action.

## Automated verification

The following command completed successfully against the final app source:

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

- 48 tests: 18 command tests, 12 matching tests, 18 Android lifecycle tests.
- Zero test failures or test errors.
- Debug APK built successfully, versionName `0.7.1`, versionCode `20`.
- Android lint: zero errors, 42 warnings. Existing warnings cover newer dependency versions, hardcoded/translatable text, custom-view tooling/accessibility declarations, backup metadata, icon metadata and obsolete SDK checks. They were not hidden with a lint baseline.
- Regression tests were run before their corresponding fixes and reproduced the failures. Logs are retained under `app/build/review/`.
- Independent source reviews were repeated after the main fixes and callback/controller fixes.

Reports:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Device smoke test and limits

The final APK installed and cold-launched successfully on the connected Android API 37 phone. The setup screen was visually inspected; its screenshot is `app/build/review/device-startup.png`. Startup log inspection found no application crash. The phone has no saved car and the voice model has not been downloaded.

The 48 automated tests include simulated Android API 35 lifecycle behavior. They cannot establish real A2DP routing, OEM background-service behavior, native Vosk capture, call interruption, or real GPS accuracy. Complete the real-car checks in `TESTING.md` before treating this as a release validated on your car. These results establish that the identified bugs are fixed and the checks pass; they cannot prove the absence of every possible undiscovered bug.

## Follow-up review (October 30, 2026, later)

A second full read of the source found three more bugs, now fixed. **These edits have not been compiled or tested yet**: the review machine couldn't reach the Gradle/Google Maven servers. Run the verification command above before installing.

- **Go countdown froze on misheard words.** Saying "Hey James" during the 3-2-1 countdown pauses it; if the next words weren't Go, Cancel or a command (e.g. road noise heard as "banana"), the countdown stayed stopped until the sheet auto-closed and the trip was lost. A word that happened to resemble a playlist name could also replace the trip with music. It now restarts the countdown, the same as when nothing is heard. Regression test: `LifecycleTest.misheardWordsDuringCountdownResumeTheTrip`.
- **Renaming a place could create duplicates.** The "already have a place called …" check only ran when adding, so editing "Gym" and renaming it "Church" while "Church" existed left two Church entries. The check now runs on edits too (renaming to the same name in different capitals is still allowed).
- **Lost "done" callback while text-to-speech starts up.** If two sentences were queued before TTS finished starting, the first sentence's callback was dropped, which could leave the "Hey James" listener paused until the sheet closed. The replaced callback now still runs (the callers' tokens make that harmless).
- **"Sorry, I didn't catch that" now keeps listening.** After the wake word, if James hears nothing usable (or words that aren't a command or playlist), he says "Sorry, I didn't catch that. Say it again." and listens again straight away, so you just repeat yourself. After two retries he gives up and says to use "Hey James" again. During the Go countdown the old behaviour stays (the countdown simply resumes). Regression test: `LifecycleTest.notCatchingACommandListensAgainWithoutTheWakeWord`.
