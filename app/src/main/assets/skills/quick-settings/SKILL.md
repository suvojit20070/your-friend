---
name: Quick settings
description: Turn Wi-Fi, Bluetooth, mobile data, brightness, volume, flashlight and do-not-disturb on or off quickly.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [wifi, bluetooth, data, brightness, volume, torch, flashlight, dnd, silent]
required_tools: [device_admin, open_uri, screen_operation_accessibility]
---

# Quick settings

1. With Shizuku (`device_admin`): wifi / bluetooth / mobile_data on|off, brightness 0–255, volume per stream —
   one call, no screens. Use this first.
2. Without Shizuku: open the system panel and tap — Wi-Fi panel `android.settings.panel.action.WIFI`,
   internet `android.settings.panel.action.INTERNET_CONNECTIVITY`, volume `android.settings.panel.action.VOLUME`
   (open via the Settings app with `launch_app` if a panel can't be opened), then `screen_operation_accessibility`.
3. Flashlight / DND: pull down quick settings (`screen_operation_accessibility` global action notifications or
   quick_settings) and tap the tile.
4. Say what you changed in a few words.
