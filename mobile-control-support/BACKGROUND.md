# Background display prototype

`ui_display` starts a shell-side virtual display so an app can be launched away from the user's main screen.

Requirements:

- Shizuku or root (shell UID);
- Android 12+ recommended, Android 14+ for reliable per-display focus;
- the accessibility service enabled.

Flow:

```text
ui_display action=start
ui_display action=launch package=org.telegram.messenger
ui_snapshot display=background
ui_act <ref> action=tap
ui_snapshot display=background
ui_display action=stop
```

What this prototype does:

- creates a public off-screen display without a Surface;
- launches a launcher activity with `am start --display`;
- reads the accessibility tree on that display;
- performs node actions through the accessibility service.

What it does **not** do:

- no ImageReader/live preview;
- no OCR or vision fallback;
- no guarantee that every app supports secondary displays;
- no bypass of `FLAG_SECURE`, login, CAPTCHA, MFA, payment, or permission screens;
- no hidden-screen claim until it is tested on a real device.

If `ui_display` cannot start (missing shell, helper failure, empty snapshot), fall back to the foreground accessibility loop and report why. Do not leave the helper running.
