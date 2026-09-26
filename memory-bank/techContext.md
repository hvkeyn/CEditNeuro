# Tech context

- Kotlin, Jetpack Compose Material3 (icons-extended), Gradle Kotlin DSL, Windows + PowerShell host.
- Build and test: `.\gradlew.bat --console=plain testDebugUnitTest assembleRelease`. The release APK also uses the `.debug` suffix and is what the phone runs.
- Phone: Samsung SM-S942B, Android 16, 1080×2340. Always pass `adb -s` with the quoted TLS serial. Screenshots via `adb exec-out screencap -p`. Shizuku is running (uid 2000).
- PowerShell pitfall: a one-pair `@(@(a,b))` flattens; string replace loops then work on characters. Use the edit tool for code edits.
- `adb shell input text` needs the argument quoted inside the device shell when it contains `;`.
