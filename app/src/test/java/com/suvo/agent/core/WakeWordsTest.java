package com.suvo.agent.core;

import com.suvo.agent.core.util.WakeWords;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WakeWordsTest {

    private static final List<String[]> WORDS = WakeWords.parse("tanu, hey tanu, hi alex");

    @Test
    public void noWakeWordsMeansEverythingTriggers() {
        WakeWords.Match m = WakeWords.match("open youtube", WakeWords.parse(""));
        assertTrue(m.triggered);
        assertEquals("open youtube", m.command);
    }

    @Test
    public void commandAfterWakeWord() {
        WakeWords.Match m = WakeWords.match("Hey Tanu, open YouTube please", WORDS);
        assertTrue(m.triggered);
        assertEquals("open youtube please", m.command);
    }

    @Test
    public void wakeWordInTheMiddleOrAtTheEnd() {
        assertEquals("what time is it", WakeWords.match("ok tanu what time is it", WORDS).command);
        assertEquals("open youtube", WakeWords.match("open youtube tanu", WORDS).command);
    }

    @Test
    public void onlyTheWakeWord() {
        WakeWords.Match m = WakeWords.match("Hi Alex!", WORDS);
        assertTrue(m.triggered);
        assertEquals("", m.command);
    }

    @Test
    public void ignoresOtherSpeechAndToleratesSlips() {
        assertFalse(WakeWords.match("turn up the volume", WORDS).triggered);
        assertTrue(WakeWords.match("hey tanoo call mom", WORDS).triggered);
        assertFalse(WakeWords.match("tan the leather", WORDS).triggered);
    }

    @Test
    public void otherScripts() {
        List<String[]> w = WakeWords.parse("তনু, तनु");
        assertTrue(WakeWords.match("তনু আজকের আবহাওয়া কেমন", w).triggered);
        assertEquals("आज का मौसम", WakeWords.match("तनु आज का मौसम", w).command);
    }
}
