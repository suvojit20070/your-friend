package com.suvo.agent.core.character;

import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * What the AI is asked when a song plays: think how people dance to THIS song, then write the
 * dance as a {@link DanceRecord} (every joint, every beat) — or decide that this is not a song to
 * dance to and say something kind instead. Later it is shown its own record and asked to improve
 * it. Pure Java.
 */
public final class DanceCoach {

    private DanceCoach() {
    }

    public static String systemPrompt(String name, String language) {
        return "You are the choreographer and the body of " + (empty(name) ? "a small anime companion girl" : name + ", a small anime companion girl")
                + " who lives on the user's phone screen. A song is playing on the phone. From what you know about this song "
                + "(its mood, genre, era, language, the film or artist, its famous hook step if it has one, how people really dance to it "
                + "at parties, weddings, in reels), write her dance for it as a motion recording: for each joint, which angle at which beat.\n\n"
                + "FIRST decide the mood honestly from the title and artist (words like sad, slowed, reverb, lofi, heartbreak, alone, "
                + "broken, bhajan, prayer, lullaby, sleep, study, rain sounds tell you a lot).\n"
                + "- Songs people do not dance to (very sad, grief, heartbreak, devotional, lullaby, speech, podcast, news): answer "
                + "{\"mood\":\"…\",\"dance\":false,\"face\":\"sad\" or \"neutral\",\"say\":\"one short warm line\"} — no phrases. "
                + "\"say\" is spoken to the user: gentle company or a little encouragement for a sad song, never a joke about their taste. "
                + "Write \"say\" in " + (empty(language) ? "the language of the song title" : language) + ", at most 12 words.\n"
                + "- Slow, romantic, soft songs: a small, slow, graceful dance — swaying, weight moving from foot to foot, soft hands, "
                + "head tilts, eyes closing moments. hp y stays above −4, no lifted feet higher than 6, arms mostly below the shoulders.\n"
                + "- Happy, upbeat, party songs: bigger, bouncier, with steps, claps, arm lines, hip accents, the hook step if you know it.\n\n"
                + "MAKE IT HUMAN, NOT A ROBOT (anime-girl cute, aesthetic):\n"
                + "- No two sides doing the same thing at the same time all the time: lead with one hand, the other follows 0.25–0.75 "
                + "beats later; the head and chest react after the hips.\n"
                + "- Weight always goes somewhere: shift w before a step; let the hips drop a little (hp y −2…−6) on the beat and rise after.\n"
                + "- Use follow-through: after a big arm move add a small settling key; do not hold a pose frozen for more than 2 beats.\n"
                + "- Wrists and fingers matter: soft curled hands (curl 0.2–0.4) for slow songs, open hands on accents, turn the wrist.\n"
                + "- Pauses are part of dancing: put \"rest:1\" … \"rest:4\" between phrases now and then.\n"
                + "- Write 2 to 4 phrases of 4 or 8 beats, 10–24 keys each, covering about 30 seconds in \"order\". "
                + "Keys may be at any fraction of a beat. Keep every value inside its range.\n"
                + "- She stands in a small spot: feet stay within the ranges; no turning the back to the viewer.\n\n"
                + DanceRecord.formatHelp();
    }

    public static String firstRequest(String title, String artist, String app, float measuredBpm) {
        StringBuilder b = new StringBuilder();
        b.append("Song: ").append(title.trim());
        if (!empty(artist)) b.append(" — ").append(artist.trim());
        if (!empty(app)) b.append(" (playing in ").append(app.trim()).append(')');
        b.append('\n').append(measuredBpm > 0 ? String.format(Locale.ROOT, "Measured tempo: %.0f BPM (use it for \"bpm\").", measuredBpm)
                : "The tempo could not be measured: give the song's real tempo in \"bpm\".");
        b.append("\nThink how people dance to this song, then answer with the JSON only.");
        return b.toString();
    }

    public static String improveRequest(String title, String artist, float measuredBpm, String previousJson) {
        StringBuilder b = new StringBuilder();
        b.append("The same song is still playing: ").append(title.trim());
        if (!empty(artist)) b.append(" — ").append(artist.trim());
        if (measuredBpm > 0) b.append(String.format(Locale.ROOT, " (measured tempo %.0f BPM)", measuredBpm));
        b.append(".\nThis is the recording you wrote for it; she has been dancing it:\n").append(previousJson)
                .append("\nLook at it critically as a dance teacher. What looks stiff, robotic, too symmetric, too fast or too big for this song? "
                        + "What is missing (weight shifts, wrist and head details, a pause, the song's own character)? "
                        + "Answer with the improved, complete recording (same format, JSON only): keep what works, fix what does not, "
                        + "and add one new phrase so the dance grows with the song.");
        return b.toString();
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
