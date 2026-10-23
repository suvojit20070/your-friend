package com.suvo.agent.core;

import com.suvo.agent.core.util.HtmlText;
import com.suvo.agent.core.util.UrlSafety;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebUtilTest {

    @Test
    public void extractsReadableText() {
        String html = "<html><head><title>Kolkata &amp; Weather</title>"
                + "<meta name=\"description\" content=\"Today's forecast\"><style>.x{}</style></head>"
                + "<body><nav>Home | About</nav><script>alert(1)</script>"
                + "<h1>Forecast</h1><p>Sunny, 31&deg;C &mdash; humid.</p><ul><li>Morning: clear</li><li>Evening: rain</li></ul>"
                + "<a href=\"/radar\">Radar map</a><footer>Copyright</footer></body></html>";
        HtmlText.Page p = HtmlText.extract(html, "https://weather.example/kolkata", 5000);
        assertEquals("Kolkata & Weather", p.title);
        assertEquals("Today's forecast", p.description);
        assertTrue(p.text.contains("# Forecast"));
        assertTrue(p.text.contains("Sunny"));
        assertTrue(p.text.contains("— humid"));
        assertTrue(p.text.contains("- Morning: clear"));
        assertFalse(p.text.contains("alert"));
        assertFalse(p.text.contains("Home | About"));
        assertFalse(p.text.contains("Copyright"));
        assertEquals("https://weather.example/radar", p.links.get(0)[1]);
    }

    @Test
    public void blocksLocalTargets() {
        assertNotNull(UrlSafety.check("http://127.0.0.1:8080/admin", false));
        assertNotNull(UrlSafety.check("http://localhost/", false));
        assertNotNull(UrlSafety.check("http://192.168.1.1/", false));
        assertNotNull(UrlSafety.check("http://10.0.0.5/", false));
        assertNotNull(UrlSafety.check("file:///etc/passwd", false));
        assertNotNull(UrlSafety.check("ftp://example.com/x", false));
        assertNotNull(UrlSafety.check("https://user:pass@example.com/", false));
        assertNull(UrlSafety.check("http://192.168.1.1/", true));
    }
}
