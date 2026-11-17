# What To Watch (Android TV)

A Netflix-style "what should I watch?" screen for an Android TV box. It shows rows of
trending and popular movies and shows (from TMDB), keeps your own **My List**, and opens
your VOD app (e.g. VOD Ultra or Heat VOD) to watch the title you picked.

## Install on the box
1. Install **Downloader** (by AFTVnews) on the box.
2. In Downloader, enter:
   `https://github.com/<your-username>/what-to-watch-tv/releases/latest/download/WhatToWatch.apk`
3. Allow the install if asked, then open **What To Watch**.
4. First time you press **Watch**, pick your VOD app from the list.

## Update
Any change pushed to `main` is built automatically by GitHub Actions. Re-run the same
Downloader link to install the new version over the old one (your list is kept).

## Remove / reset
Box Settings → Apps → What To Watch → Uninstall. Nothing else on the box is changed.

## Build secrets (GitHub → Settings → Secrets and variables → Actions)
- `TMDB_KEY` — your TMDB API key or read access token
- `KEYSTORE_B64`, `KEYSTORE_PASSWORD` — the app's signing key (keeps updates installable)
