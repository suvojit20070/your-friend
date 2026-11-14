---
name: Play music
description: Play a song, artist or playlist on YouTube Music, Spotify or YouTube.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [play, song, gaana, music, bajao, chalao]
required_tools: [open_uri, launch_app, screen_operation_accessibility]
---

# Play music

1. Fastest: `open_uri` with a search link the music app opens directly:
   - YouTube Music: `https://music.youtube.com/search?q=<song+artist>`
   - Spotify: `spotify:search:<song artist>`
   - YouTube: `https://www.youtube.com/results?search_query=<song+artist>`
   Prefer the app the user uses (ask once, then remember it with `memory`).
2. Then `screen_operation_accessibility` read → tap the first matching song to start it.
3. Pause / next: use the media notification or the app's buttons.
4. Say only the song name you started.
