package com.suvo.agent.connectors;

import com.suvo.agent.core.util.HtmlText;
import com.suvo.agent.tools.J;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.search.AndTerm;
import javax.mail.search.BodyTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.FromStringTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.RecipientStringTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;
import javax.mail.util.ByteArrayDataSource;

/**
 * Your Friend — Original project by Suvo.
 *
 * A mailbox over IMAP (reading, searching, organising) and SMTP (sending), for the email
 * connectors. Messages are addressed by their IMAP UID within a folder, which stays the same
 * between calls. Blocking; call off the main thread.
 */
public final class EmailClient {

    private final Connector c;

    public EmailClient(Connector c) {
        this.c = c;
    }

    private static final int TIMEOUT = 30_000;

    private Store openStore() throws MessagingException {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", c.imapHost);
        p.put("mail.imaps.port", String.valueOf(c.imapPort));
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.connectiontimeout", String.valueOf(TIMEOUT));
        p.put("mail.imaps.timeout", String.valueOf(TIMEOUT));
        Session s = Session.getInstance(p);
        Store st = s.getStore("imaps");
        st.connect(c.imapHost, c.imapPort, c.username, c.password);
        return st;
    }

    /** Checks both servers; returns null when everything works, else what failed. */
    public String test() {
        try (Store st = openStore()) {
            st.getDefaultFolder();
        } catch (Exception e) {
            return "IMAP: " + e.getMessage();
        }
        try {
            Transport t = smtpSession().getTransport(c.smtpSsl ? "smtps" : "smtp");
            t.connect(c.smtpHost, c.smtpPort, c.username, c.password);
            t.close();
        } catch (Exception e) {
            return "SMTP: " + e.getMessage();
        }
        return null;
    }

    public JSONArray folders() throws MessagingException {
        JSONArray out = new JSONArray();
        try (Store st = openStore()) {
            for (Folder f : st.getDefaultFolder().list("*")) {
                if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                out.put(f.getFullName());
            }
        }
        return out;
    }

    private Folder open(Store st, String name, boolean write) throws MessagingException {
        Folder f = st.getFolder(name == null || name.isEmpty() ? "INBOX" : name);
        if (!f.exists()) throw new MessagingException("No folder \"" + name + "\" (use action folders)");
        f.open(write ? Folder.READ_WRITE : Folder.READ_ONLY);
        return f;
    }

    private static String addr(Address[] a) {
        if (a == null || a.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (Address x : a) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(x instanceof InternetAddress ? ((InternetAddress) x).toUnicodeString() : x.toString());
        }
        return sb.toString();
    }

    private static String date(Date d) {
        return d == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(d);
    }

    private static JSONObject summary(UIDFolder uf, Message m) throws MessagingException {
        return J.obj("uid", uf.getUID(m), "from", addr(m.getFrom()), "subject", m.getSubject() == null ? "" : m.getSubject(),
                "date", date(m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate()),
                "unread", !m.isSet(Flags.Flag.SEEN), "starred", m.isSet(Flags.Flag.FLAGGED));
    }

    /** Newest first. */
    public JSONArray list(String folder, int limit, boolean unreadOnly) throws MessagingException {
        JSONArray out = new JSONArray();
        try (Store st = openStore()) {
            Folder f = open(st, folder, false);
            try {
                Message[] msgs;
                if (unreadOnly) {
                    msgs = f.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                } else {
                    int n = f.getMessageCount();
                    msgs = n == 0 ? new Message[0] : f.getMessages(Math.max(1, n - limit + 1), n);
                }
                fetch(f, msgs);
                UIDFolder uf = (UIDFolder) f;
                for (int i = msgs.length - 1; i >= 0 && out.length() < limit; i--) out.put(summary(uf, msgs[i]));
            } finally {
                f.close(false);
            }
        }
        return out;
    }

    private static void fetch(Folder f, Message[] msgs) throws MessagingException {
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(UIDFolder.FetchProfileItem.UID);
        f.fetch(msgs, fp);
    }

    /** Search by sender, recipient, subject, words, date and unread; all given parts must match. */
    public JSONArray search(String folder, JSONObject q, int limit) throws Exception {
        List<SearchTerm> terms = new ArrayList<>();
        if (!q.optString("from").isEmpty()) terms.add(new FromStringTerm(q.optString("from")));
        if (!q.optString("to").isEmpty()) terms.add(new RecipientStringTerm(Message.RecipientType.TO, q.optString("to")));
        if (!q.optString("subject").isEmpty()) terms.add(new SubjectTerm(q.optString("subject")));
        if (!q.optString("text").isEmpty()) {
            String t = q.optString("text");
            terms.add(new OrTerm(new SubjectTerm(t), new BodyTerm(t)));
        }
        if (!q.optString("since").isEmpty()) {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(q.optString("since"));
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, d));
        }
        if (!q.optString("before").isEmpty()) {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(q.optString("before"));
            terms.add(new ReceivedDateTerm(ComparisonTerm.LT, d));
        }
        if (q.optBoolean("unread", false)) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        if (terms.isEmpty()) return list(folder, limit, false);
        SearchTerm term = terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(new SearchTerm[0]));
        JSONArray out = new JSONArray();
        try (Store st = openStore()) {
            Folder f = open(st, folder, false);
            try {
                Message[] msgs = f.search(term);
                fetch(f, msgs);
                UIDFolder uf = (UIDFolder) f;
                for (int i = msgs.length - 1; i >= 0 && out.length() < limit; i--) out.put(summary(uf, msgs[i]));
            } finally {
                f.close(false);
            }
        }
        return out;
    }

    private static Message byUid(Folder f, long uid) throws MessagingException {
        Message m = ((UIDFolder) f).getMessageByUID(uid);
        if (m == null) throw new MessagingException("No message with uid " + uid + " in " + f.getFullName());
        return m;
    }

    /** One message: headers, readable text, attachment names. Marks it read when {@code markRead}. */
    public JSONObject read(String folder, long uid, boolean markRead, int maxChars) throws Exception {
        try (Store st = openStore()) {
            Folder f = open(st, folder, markRead);
            try {
                Message m = byUid(f, uid);
                StringBuilder plain = new StringBuilder(), html = new StringBuilder();
                List<String> files = new ArrayList<>();
                walk(m, plain, html, files);
                String text = plain.length() > 0 ? plain.toString()
                        : HtmlText.extract(html.toString(), "", maxChars).text;
                if (text.length() > maxChars) text = text.substring(0, maxChars) + "\n… (cut)";
                JSONObject o = summary((UIDFolder) f, m);
                J.put(o, "to", addr(m.getRecipients(Message.RecipientType.TO)));
                J.put(o, "cc", addr(m.getRecipients(Message.RecipientType.CC)));
                J.put(o, "reply_to", addr(m.getReplyTo()));
                J.put(o, "text", text);
                J.put(o, "attachments", new JSONArray(files));
                if (markRead) m.setFlag(Flags.Flag.SEEN, true);
                return o;
            } finally {
                f.close(false);
            }
        }
    }

    private static void walk(Part p, StringBuilder plain, StringBuilder html, List<String> files) throws Exception {
        String disp = p.getDisposition();
        String name = p.getFileName();
        if (Part.ATTACHMENT.equalsIgnoreCase(disp) || (name != null && !p.isMimeType("multipart/*"))) {
            if (name != null) files.add(name);
            return;
        }
        if (p.isMimeType("text/plain")) {
            plain.append(p.getContent()).append('\n');
        } else if (p.isMimeType("text/html")) {
            html.append(p.getContent()).append('\n');
        } else if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), plain, html, files);
        } else if (p.isMimeType("message/rfc822")) {
            walk((Part) p.getContent(), plain, html, files);
        }
    }

    /** The bytes of one attachment (by file name). */
    public byte[] attachment(String folder, long uid, String fileName, int maxBytes) throws Exception {
        try (Store st = openStore()) {
            Folder f = open(st, folder, false);
            try {
                Part p = findAttachment(byUid(f, uid), fileName);
                if (p == null) throw new IOException("No attachment named \"" + fileName + "\"");
                try (InputStream in = p.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > maxBytes) throw new IOException("Attachment is larger than " + (maxBytes >> 20) + " MB");
                    }
                    return bos.toByteArray();
                }
            } finally {
                f.close(false);
            }
        }
    }

    private static Part findAttachment(Part p, String fileName) throws Exception {
        if (fileName.equals(p.getFileName())) return p;
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                Part r = findAttachment(mp.getBodyPart(i), fileName);
                if (r != null) return r;
            }
        }
        return null;
    }

    public void setFlags(String folder, long uid, Boolean read, Boolean starred) throws MessagingException {
        try (Store st = openStore()) {
            Folder f = open(st, folder, true);
            try {
                Message m = byUid(f, uid);
                if (read != null) m.setFlag(Flags.Flag.SEEN, read);
                if (starred != null) m.setFlag(Flags.Flag.FLAGGED, starred);
            } finally {
                f.close(false);
            }
        }
    }

    public void move(String folder, long uid, String to) throws MessagingException {
        try (Store st = openStore()) {
            Folder f = open(st, folder, true);
            try {
                Folder dest = st.getFolder(to);
                if (!dest.exists()) throw new MessagingException("No folder \"" + to + "\"");
                Message m = byUid(f, uid);
                if (f instanceof com.sun.mail.imap.IMAPFolder) {
                    ((com.sun.mail.imap.IMAPFolder) f).moveMessages(new Message[]{m}, dest);
                } else {
                    f.copyMessages(new Message[]{m}, dest);
                    m.setFlag(Flags.Flag.DELETED, true);
                    f.expunge();
                }
            } finally {
                f.close(true);
            }
        }
    }

    /** To the trash (Gmail keeps it 30 days); elsewhere marked deleted and expunged. */
    public String delete(String folder, long uid) throws MessagingException {
        try (Store st = openStore()) {
            Folder trash = null;
            for (String n : new String[]{"[Gmail]/Trash", "[Gmail]/Bin", "Trash", "Deleted", "Deleted Items", "Deleted Messages"}) {
                Folder t = st.getFolder(n);
                if (t.exists()) {
                    trash = t;
                    break;
                }
            }
            Folder f = open(st, folder, true);
            try {
                Message m = byUid(f, uid);
                if (trash != null && !trash.getFullName().equals(f.getFullName()) && f instanceof com.sun.mail.imap.IMAPFolder) {
                    ((com.sun.mail.imap.IMAPFolder) f).moveMessages(new Message[]{m}, trash);
                    return "moved to " + trash.getFullName();
                }
                m.setFlag(Flags.Flag.DELETED, true);
                f.expunge();
                return "deleted";
            } finally {
                f.close(true);
            }
        }
    }

    private Session smtpSession() {
        Properties p = new Properties();
        String proto = c.smtpSsl ? "smtps" : "smtp";
        p.put("mail.transport.protocol", proto);
        p.put("mail." + proto + ".host", c.smtpHost);
        p.put("mail." + proto + ".port", String.valueOf(c.smtpPort));
        p.put("mail." + proto + ".auth", "true");
        p.put("mail." + proto + ".connectiontimeout", String.valueOf(TIMEOUT));
        p.put("mail." + proto + ".timeout", String.valueOf(TIMEOUT));
        if (!c.smtpSsl) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return Session.getInstance(p);
    }

    public static final class Attachment {
        public final String name, type;
        public final byte[] data;

        public Attachment(String name, String type, byte[] data) {
            this.name = name;
            this.type = type;
            this.data = data;
        }
    }

    /**
     * Sends a message. With {@code replyFolder}/{@code replyUid} it is a reply to that message
     * (recipients and subject taken from it when not given, threading headers set).
     */
    public String send(String to, String cc, String bcc, String subject, String body, boolean isHtml,
                       List<Attachment> files, String replyFolder, long replyUid) throws Exception {
        Session s = smtpSession();
        MimeMessage msg = new MimeMessage(s);
        msg.setFrom(new InternetAddress(c.username));
        if (replyUid > 0) {
            try (Store st = openStore()) {
                Folder f = open(st, replyFolder, false);
                try {
                    MimeMessage orig = (MimeMessage) byUid(f, replyUid);
                    MimeMessage r = (MimeMessage) orig.reply(false);
                    if (to == null || to.isEmpty()) to = addr(r.getRecipients(Message.RecipientType.TO));
                    if (subject == null || subject.isEmpty()) subject = r.getSubject();
                    String[] irt = r.getHeader("In-Reply-To");
                    String[] refs = r.getHeader("References");
                    if (irt != null) msg.setHeader("In-Reply-To", irt[0]);
                    if (refs != null) msg.setHeader("References", refs[0]);
                } finally {
                    f.close(false);
                }
            }
        }
        if (to == null || to.trim().isEmpty()) throw new MessagingException("No recipient (to)");
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        if (cc != null && !cc.isEmpty()) msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc, false));
        if (bcc != null && !bcc.isEmpty()) msg.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc, false));
        msg.setSubject(subject == null ? "" : subject, "UTF-8");
        msg.setSentDate(new Date());
        if (files == null || files.isEmpty()) {
            if (isHtml) msg.setContent(body, "text/html; charset=UTF-8");
            else msg.setText(body, "UTF-8");
        } else {
            MimeMultipart mp = new MimeMultipart();
            MimeBodyPart text = new MimeBodyPart();
            if (isHtml) text.setContent(body, "text/html; charset=UTF-8");
            else text.setText(body, "UTF-8");
            mp.addBodyPart(text);
            for (Attachment a : files) {
                BodyPart bp = new MimeBodyPart();
                bp.setDataHandler(new DataHandler(new ByteArrayDataSource(a.data, a.type)));
                bp.setFileName(a.name);
                mp.addBodyPart(bp);
            }
            msg.setContent(mp);
        }
        Transport t = s.getTransport(c.smtpSsl ? "smtps" : "smtp");
        try {
            t.connect(c.smtpHost, c.smtpPort, c.username, c.password);
            t.sendMessage(msg, msg.getAllRecipients());
        } finally {
            t.close();
        }
        return msg.getMessageID() == null ? "" : msg.getMessageID();
    }
}
