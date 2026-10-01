# Tech context

- Kotlin, Jetpack Compose Material3 (icons-extended), Gradle Kotlin DSL, Windows + PowerShell host.
- `webviewshell` is a second application module. Its debug APK is copied into the app assets as `web-shell.apk` before each app build. `com.android.tools.build:apksig` re-signs wrappers on the device.
- Build and test: `.\gradlew.bat --console=plain testDebugUnitTest assembleRelease`. The release APK also uses the `.debug` suffix and is what the phone runs.
- 16 KB pages: `tools/align_elf_16k.py` rewrites stripped `.so` files, then `zipalign -P 16` and `apksigner` run at the end of `packageDebug` / `packageRelease`. AGP 8.7 has no `-P` zipalign. The device flags a library only when a LOAD `p_align` is 0x1000. The warning string is cached across updates.
- Phone: Samsung SM-S942B, Android 16, 1080×2340. Always pass `adb -s` with the quoted TLS serial. Screenshots via `adb exec-out screencap -p`. Shizuku is running (uid 2000).
- PowerShell pitfall: a one-pair `@(@(a,b))` flattens; string replace loops then work on characters. Use the edit tool for code edits.
- `adb shell input text` needs the argument quoted inside the device shell when it contains `;`.
