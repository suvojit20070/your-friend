package com.suvo.agent.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * What a user message has attached, read back from the message itself (the notes
 * {@link Attachments} writes, and its images), so the chat can show real previews instead of a
 * "📎 name" line. Pure Java.
 */
public final class AttachmentList {

    public static final int IMAGE = 0, VIDEO = 1, PDF = 2, FILE = 3, TEXT = 4;

    public static final class Att {
        public int kind;
        public String name = "";
        /** Workspace path ("" for an image that was only sent to the model). */
        public String path = "";
        /** IMAGE without a file: index into the message's images. */
        public int image = -1;
        /** "12.5 KB, application/zip" (FILE), "3 pages" (PDF), "12.0 s, 1920x1080" (VIDEO). */
        public String info = "";

        @Override
        public int hashCode() {
            return java.util.Objects.hash(kind, name, path, image, info);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Att)) return false;
            Att a = (Att) o;
            return kind == a.kind && image == a.image && name.equals(a.name) && path.equals(a.path) && info.equals(a.info);
        }
    }

    private static final Pattern NOTE = Pattern.compile(
            "^\\[Attachment\\] (File|Video|PDF|Image) \"([^\"]*)\"(.*)$", Pattern.MULTILINE);
    private static final Pattern PATH = Pattern.compile("at path \"([^\"]*)\"");
    private static final Pattern PAREN = Pattern.compile("^ \\(([^)]*)\\)");
    private static final Pattern FRAMES = Pattern.compile(": (\\d+) frames are attached");
    private static final Pattern PAGES_SHOWN = Pattern.compile(": pages? 1(?:–(\\d+))? (?:is|are) attached");
    private static final Pattern BLOCK = Pattern.compile("<file name=\"([^\"]*)\" workspace_path=\"([^\"]*)\" chars=\"(\\d+)\"");

    private AttachmentList() {
    }

    /** The attachments of a message, in order: files and media from its notes, then plain images. */
    public static List<Att> of(String content, int imageCount) {
        List<Att> out = new ArrayList<>();
        int framesUsed = 0;
        if (content != null && (content.contains("[Attachment]") || content.contains("<file name="))) {
            Matcher m = NOTE.matcher(content);
            while (m.find()) {
                String kind = m.group(1), rest = m.group(3);
                Att a = new Att();
                a.name = m.group(2);
                Matcher p = PATH.matcher(rest);
                if (p.find()) a.path = p.group(1);
                Matcher par = PAREN.matcher(rest);
                if (par.find()) a.info = par.group(1);
                switch (kind) {
                    case "Video": {
                        a.kind = VIDEO;
                        Matcher f = FRAMES.matcher(rest);
                        if (f.find()) framesUsed += Integer.parseInt(f.group(1));
                        break;
                    }
                    case "PDF": {
                        a.kind = PDF;
                        Matcher f = PAGES_SHOWN.matcher(rest);
                        if (f.find()) framesUsed += f.group(1) == null ? 1 : Integer.parseInt(f.group(1));
                        break;
                    }
                    case "Image":
                        continue; // shown from the images below
                    default:
                        a.kind = isImageName(a.name) ? IMAGE : FILE;
                }
                out.add(a);
            }
            Matcher b = BLOCK.matcher(content);
            while (b.find()) {
                Att a = new Att();
                a.kind = TEXT;
                a.name = b.group(1);
                a.path = b.group(2);
                a.info = b.group(3) + " chars";
                out.add(a);
            }
        }
        for (int i = framesUsed; i < imageCount; i++) {
            Att a = new Att();
            a.kind = IMAGE;
            a.image = i;
            out.add(a);
        }
        return out;
    }

    static boolean isImageName(String n) {
        String s = n.toLowerCase(java.util.Locale.ROOT);
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp") || s.endsWith(".gif")
                || s.endsWith(".heic") || s.endsWith(".bmp");
    }
}
