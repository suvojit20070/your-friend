---
name: Alarms and timers
description: Set an alarm, a timer or a reminder quickly.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [alarm, timer, remind, reminder, wake me]
required_tools: [schedule_task, launch_app, screen_operation_accessibility]
---

# Alarms and timers

1. A reminder ("remind me at 6 to call papa") → `schedule_task` (it notifies at that time) — fastest, works locked.
2. A real alarm / timer → open the Clock app (Google: com.google.android.deskclock; Samsung:
   com.sec.android.app.clockpackage), go to Alarm / Timer, set it, then read the screen to confirm.
3. Confirm the time in one sentence ("Alarm set for 6:30 tomorrow").
