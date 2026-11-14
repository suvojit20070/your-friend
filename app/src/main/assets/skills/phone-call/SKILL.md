---
name: Phone call
description: Call someone by name or number ("papa ko call lagao") - open the dialer with the number, or find the contact.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [call, phone, dial, call karo, call lagao, phone lagao]
required_tools: [open_uri, launch_app, screen_operation_accessibility]
---

# Phone call

1. A number given → `open_uri` with `tel:<number>`; the dialer opens with it. Then tap the call button
   (`screen_operation_accessibility` read → tap the element whose text/description is "Call" / the green
   phone), unless the user only wanted the number shown.
2. A name ("papa", "Rahul") → open the Phone / Contacts app (see "Quick app open"), use the search field, type the
   name, open the right contact and tap call. If several match, ask once which one.
3. Remember names for next time with `memory` (e.g. "Papa = contact 'Papa Jio'") when the user confirms.
4. On a locked phone the dialer works after `unlock_screen`; tell the user if they must unlock.
5. Say in one sentence who you are calling before tapping call.
