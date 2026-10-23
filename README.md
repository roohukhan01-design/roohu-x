# RoohuX
YouTube search/discovery (Data API v3) + embedded IFrame playback, library, local background player.

## Setup
1. Create a YouTube Data API v3 key (Google Cloud Console).
2. Local: add `YT_API_KEY=...` to `local.properties`. CI: add repo secret `YT_API_KEY`.
3. Push to `main` → Actions → artifact `RoohuX-debug-apk`.

Notes: YouTube playback must stay visible (no background/audio-only); no quality picker is shown by the app
(the embedded player's own settings menu is used); YouTube items are not downloadable.

To rebuild after changing the GitHub Actions secret, run the **Build RoohuX APK** workflow manually or push a commit to `main`. The secret must be named `YT_API_KEY`.
