package com.suvo.agent.core.apkscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * The thinking part of the app scan, without Android: which apps are well known (a quick check is
 * enough for those when they come from the Play Store), which classes are worth reading (only the
 * suspicious parts — reading a whole app would cost far too many tokens), the prompt for the AI and
 * reading its verdict. Pure Java.
 */
public final class ScanLogic {

    private ScanLogic() {
    }

    /** Widely used apps: a copy from the Play Store (or the system) gets only a quick check. */
    private static final Map<String, String> FAMOUS = new HashMap<>();

    static {
        String[][] f = {
                {"com.facebook.katana", "Facebook"}, {"com.facebook.lite", "Facebook Lite"}, {"com.facebook.orca", "Messenger"},
                {"com.instagram.android", "Instagram"}, {"com.instagram.barcelona", "Threads"}, {"com.whatsapp", "WhatsApp"},
                {"com.whatsapp.w4b", "WhatsApp Business"}, {"org.telegram.messenger", "Telegram"}, {"org.telegram.messenger.web", "Telegram"},
                {"org.thunderdog.challegram", "Telegram X"}, {"com.openai.chatgpt", "ChatGPT"}, {"com.anthropic.claude", "Claude"},
                {"com.google.android.apps.bard", "Gemini"}, {"com.microsoft.copilot", "Microsoft Copilot"}, {"ai.perplexity.app.android", "Perplexity"},
                {"com.google.android.youtube", "YouTube"}, {"com.google.android.apps.youtube.music", "YouTube Music"},
                {"com.google.android.gm", "Gmail"}, {"com.google.android.apps.maps", "Google Maps"}, {"com.android.chrome", "Chrome"},
                {"com.google.android.apps.photos", "Google Photos"}, {"com.google.android.apps.docs", "Google Drive"},
                {"com.google.android.apps.messaging", "Google Messages"}, {"com.google.android.dialer", "Phone by Google"},
                {"com.google.android.apps.nbu.paisa.user", "Google Pay"}, {"com.google.android.googlequicksearchbox", "Google"},
                {"com.snapchat.android", "Snapchat"}, {"com.twitter.android", "X"}, {"com.zhiliaoapp.musically", "TikTok"},
                {"com.spotify.music", "Spotify"}, {"com.netflix.mediaclient", "Netflix"}, {"in.amazon.mShop.android.shopping", "Amazon"},
                {"com.amazon.mShop.android.shopping", "Amazon"}, {"com.flipkart.android", "Flipkart"}, {"com.phonepe.app", "PhonePe"},
                {"net.one97.paytm", "Paytm"}, {"in.org.npci.upiapp", "BHIM"}, {"com.truecaller", "Truecaller"}, {"com.linkedin.android", "LinkedIn"},
                {"com.pinterest", "Pinterest"}, {"com.reddit.frontpage", "Reddit"}, {"com.discord", "Discord"}, {"com.microsoft.teams", "Teams"},
                {"com.microsoft.office.outlook", "Outlook"}, {"us.zoom.videomeetings", "Zoom"}, {"com.skype.raider", "Skype"},
                {"com.viber.voip", "Viber"}, {"jp.naver.line.android", "LINE"}, {"com.tencent.mm", "WeChat"}, {"com.termux", "Termux"},
                {"com.github.android", "GitHub"}, {"com.duolingo", "Duolingo"}, {"com.ubercab", "Uber"}, {"com.application.zomato", "Zomato"},
                {"in.swiggy.android", "Swiggy"}, {"com.jio.myjio", "MyJio"}, {"com.myairtelapp", "Airtel Thanks"}, {"com.mxtech.videoplayer.ad", "MX Player"},
                {"org.videolan.vlc", "VLC"}, {"com.microsoft.office.officehubrow", "Microsoft 365"}, {"com.dropbox.android", "Dropbox"},
                {"com.canva.editor", "Canva"}, {"com.adobe.reader", "Adobe Acrobat"}, {"com.brave.browser", "Brave"},
                {"org.mozilla.firefox", "Firefox"}, {"com.opera.browser", "Opera"}, {"com.sec.android.app.sbrowser", "Samsung Internet"},
        };
        for (String[] e : f) FAMOUS.put(e[0], e[1]);
    }

    /** The well-known app's name, or null. */
    public static String famous(String pkg) {
        return pkg == null ? null : FAMOUS.get(pkg);
    }

    /** Installers that check apps before they reach the phone. */
    public static boolean trustedInstaller(String installer) {
        return "com.android.vending".equals(installer) || "com.google.android.gms".equals(installer);
    }

    /** Libraries every app ships: never worth reading. */
    private static final String[] LIBRARY_PREFIXES = {
            "android.", "androidx.", "kotlin.", "kotlinx.", "java.", "javax.", "com.google.", "com.android.", "dagger.", "okhttp3.",
            "okio.", "retrofit2.", "io.reactivex.", "rx.", "org.jetbrains.", "org.intellij.", "com.squareup.", "com.facebook.react.",
            "com.facebook.soloader.", "com.facebook.infer.", "org.chromium.", "org.json.", "org.apache.", "com.bumptech.", "io.flutter.",
            "com.airbnb.", "com.fasterxml.", "org.slf4j.", "com.crashlytics.", "io.fabric.", "com.unity3d.", "com.applovin.",
            "com.ironsource.", "com.mbridge.", "com.bytedance.sdk.", "com.appsflyer.", "com.adjust.", "com.onesignal.", "io.sentry.",
            "org.greenrobot.", "com.tencent.mmkv.", "com.yalantis.", "com.github.", "coil.", "org.reactivestreams.", "io.grpc.",
            "com.amazonaws.", "com.firebase.", "io.realm.", "org.bouncycastle.", "net.sqlcipher.", "com.journeyapps.", "com.otaliastudios.",
            "me.zhanghai.", "com.nostra13.", "com.jakewharton.", "timber.", "org.koin.", "com.mikepenz.", "com.lottie.", "org.webrtc.",
    };

    public static boolean isLibrary(String className) {
        for (String p : LIBRARY_PREFIXES) if (className.startsWith(p)) return true;
        return false;
    }

    /** Words in class names that usually mean something worth a look. */
    private static final Pattern SUSPICIOUS = Pattern.compile(
            "(?i)(sms|otp|payload|dropper|loader|dexclass|inject|hook|keylog|overlay|accessib|admin|steal|grab|spy|track|exfil|upload|"
                    + "shell|exec|root|su[A-Z_]|cmd|command|socket|c2|bot|rat|remote|crypt|cipher|decrypt|obfus|hidden|hide|stealth|"
                    + "contact|calllog|location|record|screen|capture|clipboard|notif|install|uninstall|boot|persist|wallet|bank|card|"
                    + "credential|password|login|phish|vpn|proxy|tunnel)");

    /** How suspicious a class name looks (0 = not at all). */
    public static int nameScore(String className) {
        if (isLibrary(className)) return 0;
        String simple = className.substring(className.lastIndexOf('.') + 1);
        Matcher m = SUSPICIOUS.matcher(simple);
        int score = 0;
        while (m.find()) score += 2;
        // Fully obfuscated names (a.b.c) next to a readable app package are common and say little.
        return score;
    }

    /**
     * Which classes to read: the ones the manifest gives dangerous jobs to, then the most suspicious
     * names of the app's own code — at most {@code max} in all.
     */
    public static List<String> pickClasses(List<String> fromManifest, List<String> allClasses, int max) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Set<String> all = new HashSet<>(allClasses);
        for (String c : fromManifest) {
            if (out.size() >= max) break;
            if (c != null && all.contains(c) && !isLibrary(c)) out.add(c);
        }
        List<String> ranked = new ArrayList<>();
        for (String c : allClasses) if (nameScore(c) > 0 && !out.contains(c)) ranked.add(c);
        ranked.sort((a, b) -> Integer.compare(nameScore(b), nameScore(a)));
        for (String c : ranked) {
            if (out.size() >= max) break;
            out.add(c);
        }
        return new ArrayList<>(out);
    }

    /** API uses worth pointing the AI at (found in the code that was read). */
    private static final String[][] API_HINTS = {
            {"SmsManager", "sends SMS"}, {"sendTextMessage", "sends SMS"}, {"content://sms", "reads SMS"},
            {"getDeviceId", "reads the device id"}, {"getLine1Number", "reads the phone number"},
            {"DexClassLoader", "loads code at runtime"}, {"InMemoryDexClassLoader", "loads code from memory"},
            {"Runtime.getRuntime().exec", "runs shell commands"}, {"ProcessBuilder", "runs processes"},
            {"performGlobalAction", "controls the screen (accessibility)"}, {"dispatchGesture", "taps for the user (accessibility)"},
            {"getRootInActiveWindow", "reads other apps' screens"}, {"TYPE_APPLICATION_OVERLAY", "draws over other apps"},
            {"DevicePolicyManager", "device admin powers"}, {"lockNow", "can lock the phone"}, {"wipeData", "can wipe the phone"},
            {"ClipboardManager", "reads the clipboard"}, {"MediaProjection", "records the screen"}, {"AudioRecord", "records audio"},
            {"MediaRecorder", "records audio / video"}, {"Camera", "uses the camera"}, {"getLastKnownLocation", "reads location"},
            {"ContactsContract", "reads contacts"}, {"CallLog", "reads the call log"}, {"PackageInstaller", "installs apps"},
            {"setComponentEnabledSetting", "can hide its icon"}, {"Cipher.getInstance", "encrypts / decrypts data"},
            {"Base64.decode", "decodes hidden data"}, {"WebView", "shows web pages"}, {"addJavascriptInterface", "gives web pages app access"},
            {"HttpURLConnection", "talks to a server"}, {"OkHttpClient", "talks to a server"}, {"Socket(", "opens raw connections"},
            {"getInstalledPackages", "lists your apps"}, {"getRunningAppProcesses", "watches running apps"},
            {"NotificationListenerService", "reads notifications"}, {"KeyguardManager", "checks the lock screen"},
    };

    /** The hints found in {@code code} ("sends SMS", …). */
    public static List<String> apiHints(String code) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String[] h : API_HINTS) if (code.contains(h[0])) out.add(h[1] + " (" + h[0] + ")");
        return new ArrayList<>(out);
    }

    private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{6,}");
    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{2,5})?\\b");

    /** Server addresses in the code that was read (at most 20). */
    public static List<String> addresses(String code) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = URL.matcher(code);
        while (m.find() && out.size() < 20) {
            String u = m.group();
            if (u.contains("schemas.android.com") || u.contains("www.w3.org") || u.contains("apache.org/licenses")) continue;
            out.add(u);
        }
        m = IP.matcher(code);
        while (m.find() && out.size() < 20) {
            String ip = m.group();
            if (ip.startsWith("0.") || ip.startsWith("127.") || ip.startsWith("255.")) continue;
            out.add(ip);
        }
        return new ArrayList<>(out);
    }

    public static String systemPrompt() {
        return "You are a careful Android security analyst. You get a summary of an app's manifest, the files inside it, and the "
                + "decompiled Java of only its most suspicious classes (not the whole app). Decide how risky the app is for the "
                + "person who installed it. Judge what the code actually does with each high-level permission or power (accessibility, "
                + "SMS, notifications, device admin, overlay, installing apps, loading code at runtime, sending data to servers) — "
                + "a permission that is used for the app's clear purpose is fine; hidden, unexplained or disguised use is not. Do not "
                + "call an app dangerous only because it is obfuscated or has many permissions. Never invent code you were not shown; "
                + "say when something could not be checked.\n"
                + "Answer with ONLY this JSON, no other text:\n"
                + "{\"risk\":\"safe|low|medium|high|critical\",\"verdict\":\"keep|be careful|uninstall\",\"summary\":\"2-3 plain sentences "
                + "for a normal user\",\"reasons\":[\"short reason with the evidence (class or permission)\"],\"permissions\":"
                + "[{\"permission\":\"name\",\"how\":\"how the code uses it, or 'not seen in the code that was read'\"}]}";
    }

    /** The AI's verdict. */
    public static final class Verdict {
        public String risk = "unknown", verdict = "", summary = "";
        public final List<String> reasons = new ArrayList<>();
        public final List<String[]> permissions = new ArrayList<>();
        public boolean parsed;
    }

    private static final List<String> RISKS = Arrays.asList("safe", "low", "medium", "high", "critical");

    /** Reads the AI's answer (a JSON object, possibly inside a code fence or with text around it). */
    public static Verdict parse(String raw, JsonReader json) {
        Verdict v = new Verdict();
        if (raw == null) return v;
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) {
            v.summary = raw.trim();
            return v;
        }
        try {
            json.read(raw.substring(a, b + 1), v);
            String r = v.risk.toLowerCase(Locale.ROOT).trim();
            v.risk = RISKS.contains(r) ? r : "unknown";
            v.parsed = true;
        } catch (Exception e) {
            v.summary = raw.trim();
        }
        return v;
    }

    /** Fills a verdict from JSON text (org.json on Android; kept out of this pure class). */
    public interface JsonReader {
        void read(String json, Verdict into) throws Exception;
    }
}
