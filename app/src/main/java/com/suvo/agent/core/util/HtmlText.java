package com.suvo.agent.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight readable-text extraction from HTML (no DOM library): drops scripts, styles, nav and
 * other chrome, keeps headings, paragraphs, list items and links.
 */
public final class HtmlText {

    private HtmlText() {
    }

    public static final class Page {
        public String title = "";
        public String description = "";
        public String text = "";
        public final List<String[]> links = new ArrayList<>();
    }

    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|svg|template|iframe|nav|footer|header|form|aside)\\b.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_DESC = Pattern.compile(
            "(?is)<meta[^>]+(?:name|property)\\s*=\\s*[\"'](?:description|og:description)[\"'][^>]*content\\s*=\\s*([\"'])(.*?)\\1");
    private static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*[\"']([^\"'#][^\"']*)[\"'][^>]*>(.*?)</a>");
    private static final Pattern BLOCK = Pattern.compile("(?i)</?(p|div|section|article|main|br|tr|table|ul|ol|blockquote|pre|h[1-6]|li|dd|dt)\\b[^>]*>");
    private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");
    private static final Pattern LI = Pattern.compile("(?i)<li\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    public static Page extract(String html, String baseUrl, int maxChars) {
        Page p = new Page();
        if (html == null) return p;
        String h = COMMENT.matcher(html).replaceAll(" ");
        Matcher t = TITLE.matcher(h);
        if (t.find()) p.title = clean(decode(TAG.matcher(t.group(1)).replaceAll(""))).trim();
        Matcher md = META_DESC.matcher(h);
        if (md.find()) p.description = decode(md.group(2)).trim();
        // Prefer <main> or <article> when present.
        String body = h;
        Matcher main = Pattern.compile("(?is)<(main|article)\\b[^>]*>(.*?)</\\1>").matcher(h);
        if (main.find() && main.group(2).length() > 500) body = main.group(2);
        body = DROP.matcher(body).replaceAll(" ");
        Matcher lm = LINK.matcher(body);
        Map<String, String> links = new LinkedHashMap<>();
        while (lm.find() && links.size() < 60) {
            String href = resolve(baseUrl, decode(lm.group(1)).trim());
            String label = clean(decode(TAG.matcher(lm.group(2)).replaceAll(" "))).trim();
            if (href.startsWith("http") && !label.isEmpty() && !links.containsKey(href)) links.put(href, label);
        }
        for (Map.Entry<String, String> e : links.entrySet()) p.links.add(new String[]{e.getValue(), e.getKey()});
        Matcher hm = HEADING.matcher(body);
        StringBuffer sb = new StringBuffer();
        while (hm.find()) {
            int level = Integer.parseInt(hm.group(1));
            String hashes = level <= 1 ? "#" : level == 2 ? "##" : "###"; // String.repeat needs API 33
            hm.appendReplacement(sb, Matcher.quoteReplacement("\n" + hashes + " " + TAG.matcher(hm.group(2)).replaceAll(" ").trim() + "\n"));
        }
        hm.appendTail(sb);
        body = LI.matcher(sb.toString()).replaceAll("\n- ");
        body = BLOCK.matcher(body).replaceAll("\n");
        body = TAG.matcher(body).replaceAll(" ");
        body = decode(body);
        StringBuilder out = new StringBuilder();
        String prev = "";
        for (String line : body.split("\n")) {
            String l = clean(line).trim();
            if (l.isEmpty() || l.equals("-")) continue;
            if (l.equals(prev)) continue;
            out.append(l).append('\n');
            prev = l;
        }
        p.text = TextUtil.truncate(out.toString().trim(), maxChars);
        return p;
    }

    private static String clean(String s) {
        return s.replace('\u00a0', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ");
    }

    public static String decode(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String rep;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else {
                    switch (e.toLowerCase(Locale.ROOT)) {
                        case "amp":
                            rep = "&";
                            break;
                        case "lt":
                            rep = "<";
                            break;
                        case "gt":
                            rep = ">";
                            break;
                        case "quot":
                            rep = "\"";
                            break;
                        case "apos":
                            rep = "'";
                            break;
                        case "nbsp":
                            rep = " ";
                            break;
                        case "mdash":
                            rep = "—";
                            break;
                        case "ndash":
                            rep = "–";
                            break;
                        case "hellip":
                            rep = "…";
                            break;
                        case "copy":
                            rep = "©";
                            break;
                        default:
                            rep = m.group(0);
                    }
                }
            } catch (RuntimeException ex) {
                rep = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String resolve(String base, String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        if (base == null || base.isEmpty()) return href;
        try {
            return new java.net.URI(base).resolve(href.replace(" ", "%20")).toString();
        } catch (Exception e) {
            return href;
        }
    }
}
