# Native clients

Bench's server already serves a web client from `web/`. These are native clients for the same server and the same API: nothing in them is required, and the web client stays the reference.

| | Android | macOS |
|---|---|---|
| Source | `apps/android` | `apps/macos` |
| Language | Kotlin, Jetpack Compose | Swift, SwiftUI |
| Minimum | Android 9 (API 28) | macOS 14 |
| Download | Releases tagged `android-v*` | Releases tagged `macos-v*` |

## Connecting

Both talk to the server with a paired device's token, sent as a Bearer header (the server accepts it alongside the web's cookie; see `presentedToken` in `src/server.mjs`).

- **Android** pairs once: run `bench pair` (or Settings → Pair a device) and scan the QR, or paste the link. The phone reaches the server over Tailscale (`bench share`).
- **macOS** on the same Mac as the server needs no pairing: a loopback request is let in, so it finds `http://localhost:4317` and connects. For a server elsewhere it takes a `bench pair` link and keeps the token in the keychain.

## What each does natively

- **Android:** approvals answerable from the home list; a notification when Claude needs you while the app is in the background; files shared from any app attach to a session; the transcript, tool runs and diffs as Compose lists.
- **macOS:** a menu bar window that shows what needs you and answers it in place; notifications with Allow and Deny on the banner; drag files onto a session; ⌘N new session, ⌘K go to session, ⌘. stop, ⇧⌘Return allow; Open in Terminal runs `claude --resume` in Terminal or iTerm when the server is this Mac, and copies the command when it is not.

## Themes

The eleven themes come from `web/app.css` and `web/themes.css`. `node apps/tokens.mjs` writes `Tokens.kt` and `Tokens.swift` from them; CI runs it with `--check`, so a theme changed in the stylesheet fails the native builds until they are regenerated. Change the stylesheet, never the generated files.

## Building

**Android:** `cd apps/android && ./gradlew assembleDebug` (JDK 17 and the Android SDK), or open the folder in Android Studio.

**macOS:** `brew install xcodegen`, then `cd apps/macos && xcodegen` and open `Bench.xcodeproj`. The project is generated from `project.yml` and not committed.

## Releases

Push a tag and the workflow builds and attaches the file to a GitHub release:

```bash
git tag android-v0.1.0 && git push origin android-v0.1.0   # bench-android-0.1.0.apk
git tag macos-v0.1.0 && git push origin macos-v0.1.0       # Bench-0.1.0.dmg
```

**Signing.** Without secrets, the APK is signed with a throwaway key (each release will not install over the last; uninstall first) and the Mac app is ad-hoc signed (Gatekeeper asks once: right-click Bench.app → Open). To sign properly, add these repository secrets:

- Android: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- macOS (Apple Developer Program): `MACOS_CERT_P12_BASE64`, `MACOS_CERT_PASSWORD`, `MACOS_TEAM_ID`, `APPLE_API_KEY_ID`, `APPLE_API_ISSUER`, `APPLE_API_KEY_P8_BASE64`. With these the release is signed with Developer ID and notarized.
