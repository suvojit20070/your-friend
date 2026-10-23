---
name: App navigation
description: Safely operate an app on the phone through screen control to finish a task the user asked for.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [open, tap, go to, turn on, turn off, in the app, on my phone, settings]
required_tools: [find_installed_apps, launch_app, screen_operation_accessibility]
---

# App navigation

Use this when the user asks you to do something inside an app.

1. If the right app is unclear, call `find_installed_apps` with a keyword, then `launch_app` with the package.
2. Call `screen_operation_accessibility` with `action=read`. Work only with element ids from the latest read (e1, e2 …).
3. Do one step at a time: tap / type / scroll, then look at the new screen the tool returns. If the screen did not change as expected, re-read before trying again — never repeat the same tap blindly.
4. To find something off-screen, use `action=scroll` with a direction, or `action=find` with the visible text.
5. When the task is done, stop and tell the user exactly what you changed.

Safety rules:
- Never type into password, PIN, OTP or payment fields. Ask the user to do those steps themselves.
- Before anything irreversible (sending a message, buying, deleting, posting, changing security settings) stop and ask the user to confirm, even if the tool would allow it.
- If a permission dialog or a system warning appears, describe it to the user instead of accepting it yourself.
- If you are stuck after 3 attempts on the same screen, explain what you see and ask for guidance.
