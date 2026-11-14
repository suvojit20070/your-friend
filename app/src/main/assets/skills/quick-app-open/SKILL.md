---
name: Quick app open
description: Open a well-known app in one step by its package name, without searching the installed apps first.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [open, launch, start, kholo, chalao, app]
required_tools: [launch_app]
---

# Quick app open

For a well-known app, do NOT call `find_installed_apps` first. Call `launch_app` with the package right away.
If it fails ("not installed" / not found), only then search with `find_installed_apps` (the phone may use a
different app, e.g. another browser or a maker's own dialer).

Common packages:

| App | Package |
|---|---|
| WhatsApp | com.whatsapp |
| WhatsApp Business | com.whatsapp.w4b |
| YouTube | com.google.android.youtube |
| YouTube Music | com.google.android.apps.youtube.music |
| Chrome | com.android.chrome |
| Google (search app) | com.google.android.googlequicksearchbox |
| Gmail | com.google.android.gm |
| Maps | com.google.android.apps.maps |
| Photos | com.google.android.apps.photos |
| Play Store | com.android.vending |
| Settings | com.android.settings |
| Instagram | com.instagram.android |
| Facebook | com.facebook.katana |
| Messenger | com.facebook.orca |
| Telegram | org.telegram.messenger |
| Snapchat | com.snapchat.android |
| X (Twitter) | com.twitter.android |
| LinkedIn | com.linkedin.android |
| Discord | com.discord |
| Spotify | com.spotify.music |
| Netflix | com.netflix.mediaclient |
| Amazon Prime Video | com.amazon.avod.thirdpartyclient |
| JioHotstar / Hotstar | in.startv.hotstar |
| Amazon shopping | in.amazon.mShop.android.shopping |
| Flipkart | com.flipkart.android |
| Google Pay | com.google.android.apps.nbu.paisa.user |
| PhonePe | com.phonepe.app |
| Paytm | net.one97.paytm |
| Zomato | com.application.zomato |
| Swiggy | in.swiggy.android |
| Truecaller | com.truecaller |
| Google Phone | com.google.android.dialer |
| Google Messages | com.google.android.apps.messaging |
| Google Clock | com.google.android.deskclock |
| Google Calculator | com.google.android.calculator |
| Google Calendar | com.google.android.calendar |
| Google Drive | com.google.android.apps.docs |
| Termux | com.termux |
| Shizuku | moe.shizuku.privileged.api |

Phone, Messages, Clock, Calculator and Camera differ by phone maker (Samsung: com.samsung.android.dialer,
com.samsung.android.messaging, com.sec.android.app.clockpackage, com.sec.android.app.popupcalculator;
Xiaomi: com.android.contacts / com.miui.calculator …). Try the Google one, then the maker's, then search.

For a link, a phone number or a place, `open_uri` is even faster (https:, tel:, sms:, geo:, market:).
