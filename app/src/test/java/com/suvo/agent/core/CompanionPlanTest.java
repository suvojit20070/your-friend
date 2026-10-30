package com.suvo.agent.core;

import com.suvo.agent.core.character.CompanionPlan;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CompanionPlanTest {

    @Test
    public void parsesAPlanInsideOtherText() {
        CompanionPlan p = CompanionPlan.parse("Sure!\n```json\n{\"mood\":\"Happy\",\"action\":\"walk\",\"target\":\"e3\",\"x\":420,"
                + "\"sit_style\":\"crossed\",\"look\":\"e7\",\"say\":\"ooh\",\"next_check_s\":90}\n```");
        assertEquals("happy", p.mood);
        assertEquals("walk", p.action);
        assertEquals("e3", p.target);
        assertEquals(420, p.x);
        assertEquals("crossed", p.sitStyle);
        assertEquals("e7", p.look);
        assertEquals("ooh", p.say);
        assertEquals(90, p.nextCheck);
    }

    @Test
    public void unknownValuesFallBackSafely() {
        CompanionPlan p = CompanionPlan.parse("{\"mood\":\"furious\",\"action\":\"fly\",\"target\":null,\"x\":null,"
                + "\"sit_style\":\"handstand\",\"next_check_s\":2}");
        assertEquals("", p.mood);
        assertEquals("stay", p.action);
        assertEquals("", p.target);
        assertEquals(-1, p.x);
        assertEquals("auto", p.sitStyle);
        assertEquals(CompanionPlan.MIN_NEXT, p.nextCheck);
        assertEquals("stay", CompanionPlan.parse("no json here").action);
        assertEquals("walk", CompanionPlan.parse("{\"action\":\"move\"}").action);
    }

    @Test
    public void messageCarriesStateHistoryAndElements() throws Exception {
        CompanionPlan.State st = new CompanionPlan.State();
        st.screenW = 1080;
        st.screenH = 2300;
        st.bodyW = 330;
        st.bodyH = 660;
        st.x = 900;
        st.feetY = 1800;
        st.pose = "sit";
        st.mood = "calm";
        st.on = "Search";
        JSONArray hist = new JSONArray().put(new JSONObject().put("did", "sit").put("ago_s", 80));
        String msg = CompanionPlan.userMessage(st, Arrays.asList(
                new CompanionPlan.Surface("e1", "Search", "EditText", 40, 200, 1000, 140, true, true, true, false),
                new CompanionPlan.Surface("e2", "OK", "Button", 700, 1700, 300, 120, false, true, false, false)), hist);
        JSONObject o = new JSONObject(msg);
        assertEquals(330, o.getJSONObject("character").getInt("body_w"));
        assertEquals("sit", o.getJSONObject("character").getString("pose"));
        assertEquals(1, o.getJSONArray("history").length());
        assertEquals(2, o.getJSONArray("elements").length());
        assertTrue(o.getJSONArray("elements").getJSONObject(0).getBoolean("stand"));
        assertTrue(!o.getJSONArray("elements").getJSONObject(1).has("stand"));
        assertTrue(CompanionPlan.systemPrompt("Aiko", "").contains("Aiko"));
    }

    @Test
    public void facesGesturesAgentAndScreenshotTool() {
        CompanionPlan p = CompanionPlan.parse("{\"mood\":\"bored\",\"face\":\"Surprised\",\"gesture\":\"wave\","
                + "\"agent\":\"scroll to the next reel\",\"action\":\"stay\"}");
        assertEquals("surprised", p.face);
        assertEquals("wave", p.gesture);
        assertEquals("scroll to the next reel", p.agent);
        assertTrue(!p.wantsScreenshot);
        assertEquals("", CompanionPlan.parse("{\"gesture\":\"none\",\"face\":\"evil\"}").gesture);
        assertEquals("", CompanionPlan.parse("{\"face\":\"evil\"}").face);
        assertTrue(CompanionPlan.parse("ok {\"tool\":\"screenshot\"}").wantsScreenshot);
        String withTools = CompanionPlan.systemPrompt("Aiko", "", true, true);
        assertTrue(withTools.contains("screenshot") && withTools.contains("\"agent\""));
        String without = CompanionPlan.systemPrompt("Aiko", "", false, false);
        assertTrue(!without.contains("\"tool\"") && !without.contains("\"agent\""));
    }
}
