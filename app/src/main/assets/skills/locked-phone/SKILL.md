---
name: Locked phone
description: What to do when the phone is locked - what works without unlocking, and how to unlock when needed.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [locked, lock, unlock, screen off, phone khol, lock khol]
required_tools: [unlock_screen, lock_screen]
---

# Locked phone

1. Works **without** unlocking: answering questions, alarms / timers, music, phone calls through the dialer,
   reading notifications you already have.
2. Needs the screen: opening and operating apps, reading or tapping the screen. Then call `unlock_screen` first.
3. `unlock_screen` results:
   - "Unlocked" → go on.
   - "the user has to unlock" → no unlock is saved: tell the user in one short sentence ("Please unlock the
     phone, I'll continue") and `wait` 10–15 s, then check again with `unlock_screen` (it returns at once when
     already unlocked). Don't loop more than 3 times.
   - "paused after wrong tries" / "only while charging" → tell the user, don't retry.
4. If the user asked for it, `lock_screen` again when the job is done.
5. Never ask the user to tell you their PIN — saving it is only done by the user in Settings.
