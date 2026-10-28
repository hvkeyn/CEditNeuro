# Contributing

Thanks for helping CEditoNeuro. Small, checked changes are easier to review
than a large rewrite.

## Build and test

JDK 17 and Android SDK 35 are required.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

A release APK is built with `./gradlew :app:assembleRelease` and is written to
`app/build/outputs/apk/release/`. Do not commit an APK, a signing key, a
`local.properties`, or a build directory.

## Code

- Keep the existing Kotlin and Compose style. Read the nearby code before
  adding a new abstraction.
- Add a test for new behavior and for a fixed regression. A UI change should
  also be checked on a phone or an emulator.
- Do not put an API key, token, device ID, personal path, chat transcript, or
  real user record in source, tests, screenshots, issues, or pull requests.
- A new dependency needs a concrete reason and an exit path. Prefer the
  Android platform and the libraries already in the build.
- The shell stays mksh and toybox. Do not assume Termux, `pkg`, `apt`, or root.
- A skill is Markdown that follows the contract in `SKILLS.md`. It is read only
  when selected and cannot create a tool or a permission.

## Pull requests

1. Describe the user-visible change and the check that proves it.
2. Keep unrelated formatting out of the diff.
3. Include screenshots only when they contain no personal data.
4. Mark a draft if the change is not ready to review.
5. By contributing, you agree that your contribution is licensed under the
   repository's MIT license.

## Security

Do not report a vulnerability in a public issue. Follow `SECURITY.md`.
