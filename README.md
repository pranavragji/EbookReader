# Folio — EbookReader

A minimal, fast, fully offline reader for **books, comics, manga and manhwa** on Android.
Point it at a folder and every book shows up with its cover. Read anything. Pick up on your tablet where you left off on your phone.

**[⬇ Download the latest APK](https://github.com/pranavragji/EbookReader/releases/latest/download/folio.apk)** · Android 8.0+

---

## Features

- **Your folder is your library.** Choose one or more folders; Folio indexes everything inside (recursively), extracts real covers and metadata, and keeps it fresh with pull-to-refresh.
- **Every common format.** One app for novels, PDFs, comics, manga and webtoons.
- **Reader that gets out of the way.** Tap edges to turn pages, tap the middle for controls. Themes (light, sepia, dark, true black), font, size, spacing, margins, justification, paged or scroll.
- **Manga & manhwa modes.** Right-to-left, left-to-right, vertical paged and seamless webtoon scroll, pinch / double-tap zoom, volume-key paging.
- **Sources, Mihon-style.** Browse and search **MangaDex**, and any **OPDS catalog** you add (Project Gutenberg is built in; Calibre-Web, Kavita, Komga, COPS and others all speak OPDS). Save titles to your library, download books straight into it.
- **Sync across devices.** Reading progress for books *and* manga, plus your saved titles, sync through **Google Drive** or **any WebDAV server**.
- **Your books, anywhere.** Upload books to your cloud library, download them on another device.
- **Fully offline.** Nothing leaves your device unless you turn on sync or open an online source. No accounts, no ads, no analytics.
- **Material You.** Dynamic color, light/dark/AMOLED, adaptive layout with a navigation rail on tablets.

## Supported formats

| Kind | Formats | Engine |
|---|---|---|
| Ebooks | EPUB, MOBI, AZW, AZW3 / KF8, PRC, FB2, FBZ | [foliate-js](https://github.com/johnfactotum/foliate-js) in a sandboxed WebView |
| Documents | PDF | Android `PdfRenderer`, re-rendered on zoom for crisp text |
| Comics / manga | CBZ, ZIP, CBR, RAR (RAR4), CB7, 7Z, CBT, TAR | native pager with [Telephoto](https://github.com/saket/telephoto) zoom |
| Plain | TXT, HTML | native |

EPUB/MOBI/FB2 need a reasonably current **Android System WebView** (auto-updated from the Play Store on most phones); Folio tells you if yours is too old.
Not supported: DjVu, plain PalmDOC `.prc`, DRM-protected files (Kindle DRM, Adobe ADEPT), RAR5 comics (re-pack as CBZ).

## Sync: how it works

Every book is identified by a **partial MD5 of the file** (the same scheme KOReader uses), so the same file has the same id on every device — no matter what it's called or where it lives. Folio keeps one small `folio-sync.json` (progress + saved titles) in your cloud and merges it per book, newest wins. Sync runs when you open and leave the app, and on demand.

Choose one in the **Sync** tab:

### Option A — Google Drive (Sign in with Google)

Progress is stored in Drive's hidden *app data* folder; uploaded books go to a visible **Folio** folder in your Drive. Folio can only see files it created.

Google requires each app to be registered once. One-time setup (≈5 minutes, free):

1. Open [Google Cloud Console](https://console.cloud.google.com/) → create a project (e.g. "Folio").
2. **APIs & Services → Library →** enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen →** External, app name "Folio", add your Gmail as a **test user**.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID →** type **Android**
   - Package name: `io.github.pranavragji.folio`
   - SHA-1: the fingerprint of the release signing key (see *Building* below).
5. Install the APK, open **Sync → Google Drive → Connect**.

### Option B — WebDAV (no Google setup)

Works with Nextcloud, ownCloud, Koofr, Box, a NAS (Synology / QNAP), or `rclone serve webdav`. Enter the URL, user and an app password in **Sync → WebDAV**. Files go to `/Folio/` on the server.

> Other ways to sync if you prefer: keep your book folder itself in Syncthing / FolderSync — Folio's ids are content-based, so progress still matches across devices when used with Option A or B.

## Sources

- **MangaDex** — popular, latest, search, chapter list, read online, save to library, progress sync.
- **OPDS** — tap **Browse → Add source**, paste a catalog URL (optional username/password). Downloads go straight into your library.

Folio ships no piracy sources and never will; use it with catalogs you have the right to access.

## Building

No local toolchain needed: every push builds on GitHub Actions; pushes to `master` publish a release with `folio.apk`.

Local build: JDK 17 + Android SDK 35, then `gradle :app:assembleRelease`.

**Signing.** For installable updates and a stable Google OAuth SHA-1, set two repository secrets once (keystore generated locally, never committed):

```
gh secret set KEYSTORE_B64 < release.p12.b64
gh secret set KEYSTORE_PASSWORD < password.txt
```

Without them CI signs with a throwaway key each build (updates then need an uninstall, and Google sign-in won't match).

## Architecture

Kotlin · Jetpack Compose · Material 3 · Room · DataStore · Coil 3 · OkHttp · kotlinx.serialization.

```
folio/
  data/        Room entities (Book, SavedTitle), settings
  library/     folder scanner, partial-MD5 ids, cover & metadata extraction
  reader/      foliate-js WebView reader, PDF, comic/manga reader, text
  source/      MangaDex API, OPDS parser
  sync/        sync engine, Google Drive + WebDAV backends
  ui/          library, browse, sync, settings, theme
```

## Credits & inspiration

[Mihon](https://github.com/mihonapp/mihon) (library and sources UX) · [Readest](https://github.com/readest/readest) and [Foliate](https://github.com/johnfactotum/foliate) (foliate-js) · [KOReader](https://github.com/koreader/koreader) (partial-MD5 sync ids) · [Telephoto](https://github.com/saket/telephoto) · [Coil](https://github.com/coil-kt/coil) · [junrar](https://github.com/junrar/junrar) · [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/).
Third-party licenses are listed in **Settings → About**.
