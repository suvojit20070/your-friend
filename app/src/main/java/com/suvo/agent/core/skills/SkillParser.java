package com.suvo.agent.core.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses SKILL.md files: optional YAML-style front matter between "---" lines, then Markdown.
 * Supported front-matter forms: {@code key: value}, {@code key: [a, b]}, and block lists
 * ({@code key:} followed by {@code - item} lines). No YAML features beyond that are interpreted.
 */
public final class SkillParser {

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}$");

    private SkillParser() {
    }

    public static Skill parse(String id, String markdown) {
        Skill s = new Skill();
        s.id = id == null ? "" : id;
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n");
        if (text.startsWith("﻿")) text = text.substring(1);
        String body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                String fm = text.substring(4, end);
                int bodyStart = text.indexOf('\n', end + 4);
                body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
                applyFrontMatter(s, fm);
            }
        }
        s.instructions = body.trim();
        if (s.name.isEmpty()) {
            // Fall back to the first Markdown heading.
            for (String line : body.split("\n")) {
                if (line.startsWith("# ")) {
                    s.name = line.substring(2).trim();
                    break;
                }
            }
        }
        if (s.name.isEmpty()) s.name = s.id;
        return s;
    }

    private static void applyFrontMatter(Skill s, String fm) {
        String currentListKey = null;
        List<String> currentList = null;
        for (String raw : fm.split("\n")) {
            String line = raw.replaceAll("\\s+$", "");
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            if (currentListKey != null && line.trim().startsWith("- ")) {
                currentList.add(unquote(line.trim().substring(2).trim()));
                continue;
            }
            if (currentListKey != null) {
                assignList(s, currentListKey, currentList);
                currentListKey = null;
                currentList = null;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT).replace('-', '_');
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                currentListKey = key;
                currentList = new ArrayList<>();
                continue;
            }
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = new ArrayList<>();
                for (String p : value.substring(1, value.length() - 1).split(",")) {
                    String v = unquote(p.trim());
                    if (!v.isEmpty()) items.add(v);
                }
                assignList(s, key, items);
                continue;
            }
            value = unquote(value);
            switch (key) {
                case "name":
                    s.name = value;
                    break;
                case "description":
                    s.description = value;
                    break;
                case "version":
                    s.version = value;
                    break;
                case "author":
                    s.author = value;
                    break;
                case "license":
                    s.license = value;
                    break;
                case "enabled":
                    s.enabled = !"false".equalsIgnoreCase(value);
                    break;
                case "triggers":
                case "when":
                case "trigger":
                    s.triggers.add(value);
                    break;
                default:
                    List<String> single = new ArrayList<>();
                    single.add(value);
                    assignList(s, key, single);
            }
        }
        if (currentListKey != null) assignList(s, currentListKey, currentList);
    }

    private static void assignList(Skill s, String key, List<String> items) {
        switch (key) {
            case "triggers":
            case "trigger":
            case "when":
                s.triggers.addAll(items);
                break;
            case "tools":
            case "required_tools":
            case "allowed_tools":
                s.requiredTools.addAll(items);
                break;
            case "permissions":
            case "required_permissions":
                s.requiredPermissions.addAll(items);
                break;
            case "dependencies":
            case "depends_on":
                s.dependencies.addAll(items);
                break;
            case "resources":
            case "files":
                s.resources.addAll(items);
                break;
            default:
                break;
        }
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Returns a list of validation problems; empty when the skill can be installed. */
    public static List<String> validate(Skill s, java.util.Set<String> knownTools) {
        List<String> problems = new ArrayList<>();
        if (!ID.matcher(s.id).matches()) problems.add("Skill id must be lowercase letters, digits, '.', '_' or '-' (got \"" + s.id + "\")");
        if (s.description.trim().isEmpty()) problems.add("description is required in the front matter");
        if (s.instructions.trim().isEmpty()) problems.add("SKILL.md has no instructions");
        if (s.instructions.length() > 60_000) problems.add("instructions are too long (max 60k characters)");
        for (String t : s.requiredTools) {
            if (knownTools != null && !knownTools.contains(t)) problems.add("requires unknown tool \"" + t + "\"");
        }
        for (String r : s.resources) {
            if (r.contains("..") || r.startsWith("/")) problems.add("resource path must stay inside the skill folder: " + r);
        }
        return problems;
    }
}
