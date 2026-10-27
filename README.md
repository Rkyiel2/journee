# Journée — minimalist Pronote widget for Android

A home-screen widget showing **the day's classes** (with cancellations and room/teacher changes),
**homework**, **latest grades**, the **canteen menu** and **unread messages**.
It switches to the next school day once the last class is over and refreshes every hour.

- Login through **MonLycée** (or any ENT): you sign in inside the app, which then asks Pronote for a
  *mobile app token*, the same mechanism the official app and Papillon use. Your ENT password is never stored.
- Fallback: the **QR code** from Pronote web (*Mes données → Mes informations → Code QR*).
- Pronote access is handled by [pawnote](https://github.com/LiterateInk/Pawnote.js) (GPL-3.0), bundled at build time.

## Get the APK (GitHub builds it, nothing to install)

1. Create a GitHub account if you don't have one, then **New repository** → name it `journee`
   → **Private** is fine → *Create repository*.
2. On the empty repo page, click **uploading an existing file**, drag **the contents** of this folder
   (not the folder itself) and click **Commit changes**.
   - Check that the `.github` folder came through (some systems hide folders starting with a dot).
     If it didn't: **Add file → Create new file**, name it `.github/workflows/build.yml` and paste the
     content of that file.
3. Open the **Actions** tab: the "Build APK" run takes ~5 minutes.
4. When it's green, go to **Releases** (right column of the repo's home page) on your phone and download `journee.apk`.
   Android will ask you to allow installing apps from your browser: accept.

Each new commit builds a new release. Updates install over the previous version without losing your login
(the APK is always signed with the key in `signing/`).

## First run

1. Open **Journée** → **Se connecter avec MonLycée**.
2. Sign in on monlycee.net as usual and open **Pronote**. The app detects it, then shows "Connexion à Pronote…".
3. If Pronote asks to confirm the device (double authentication), enter your PIN and pick a device name.
4. Back on the main screen: **Ajouter le widget** (or long-press the home screen → Widgets → Journée).

If the automatic step stays stuck: tap **Pronote web**, go to *Mes données → Mes informations → Code QR*,
pick a 4-digit code, then **QR à l'écran** and type that code.

If you already know your Pronote address (`https://xxxxxxx.index-education.net/pronote/`), you can paste it
on the main screen; the app goes straight to Pronote, which redirects you to MonLycée.

## Build locally (Android Studio)

```
cd js && npm install && npm run build   # bundles pawnote into app/src/main/assets/engine.js
cd .. && ./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

## How it works

| Piece | Role |
|---|---|
| `js/src/core.js` | Engine run in a hidden WebView: login (token / QR / double auth), HTTP through Kotlin |
| `js/src/snapshot.js` | Fetches timetable (8 days), homework (14 days), grades, messages, menus → compact JSON |
| `Engine.kt` / `Http.kt` | Hidden WebView + bridge; HTTP requests made by the app (no CORS) |
| `LoginActivity.kt` | MonLycée sign-in, Pronote mobile token capture, QR fallback |
| `Sync.kt` | Hourly WorkManager refresh; saves the new token Pronote returns at every login |
| `widget/` | The widget (RemoteViews list) and the row model shared with the in-app preview |

Tests for the JS glue: `cd js && npm test`.
