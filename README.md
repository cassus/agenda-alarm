# Agenda Alarm

Full-screen, alarm-style alerts for Android calendar reminders. It can skip Google Tasks to-dos that sync into the calendar.

## Build and test

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testDebugUnitTest            # filter logic
./gradlew connectedDebugAndroidTest    # end-to-end on a running emulator or device
./gradlew assembleDebug                # app/build/outputs/apk/debug/app-debug.apk
```

Headless emulator: `emulator -avd agenda36 -no-window -no-audio -no-boot-anim`.

License: MIT.
