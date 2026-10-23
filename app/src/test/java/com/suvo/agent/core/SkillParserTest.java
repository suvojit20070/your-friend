package com.suvo.agent.core;

import com.suvo.agent.core.skills.Skill;
import com.suvo.agent.core.skills.SkillParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillParserTest {

    private static final String MD = "---\n"
            + "name: Quick web search\n"
            + "description: Search the web and summarize top results\n"
            + "version: 1.2\n"
            + "author: Suvo\n"
            + "license: Apache-2.0\n"
            + "triggers: [\"user asks to look something up\", news]\n"
            + "tools:\n"
            + "  - py_web_search\n"
            + "  - py_web_read\n"
            + "permissions: [NETWORK]\n"
            + "---\n"
            + "# Quick web search\n\n1. Search.\n2. Read the best result.\n";

    @Test
    public void parsesFrontMatterAndBody() {
        Skill s = SkillParser.parse("quick-search", MD);
        assertEquals("Quick web search", s.name);
        assertEquals("Search the web and summarize top results", s.description);
        assertEquals("1.2", s.version);
        assertEquals("Suvo", s.author);
        assertEquals(Arrays.asList("user asks to look something up", "news"), s.triggers);
        assertEquals(Arrays.asList("py_web_search", "py_web_read"), s.requiredTools);
        assertEquals(Arrays.asList("NETWORK"), s.requiredPermissions);
        assertTrue(s.instructions.startsWith("# Quick web search"));
        assertTrue(s.indexLine().contains("quick-search"));
    }

    @Test
    public void validation() {
        Skill s = SkillParser.parse("quick-search", MD);
        assertTrue(SkillParser.validate(s, new HashSet<>(Arrays.asList("py_web_search", "py_web_read"))).isEmpty());
        List<String> p = SkillParser.validate(s, new HashSet<>(Arrays.asList("py_web_search")));
        assertEquals(1, p.size());
        Skill bad = SkillParser.parse("Bad Id!", "no front matter");
        assertFalse(SkillParser.validate(bad, null).isEmpty());
        Skill escape = SkillParser.parse("x", "---\ndescription: d\nresources: [../../etc/passwd]\n---\nbody");
        assertFalse(SkillParser.validate(escape, null).isEmpty());
    }

    @Test
    public void headingNameFallback() {
        Skill s = SkillParser.parse("notes", "# Daily notes\nWrite notes.");
        assertEquals("Daily notes", s.name);
    }
}
