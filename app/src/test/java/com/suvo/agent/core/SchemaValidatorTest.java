package com.suvo.agent.core;

import com.suvo.agent.core.tools.SchemaValidator;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SchemaValidatorTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"action\":{\"type\":\"string\",\"enum\":[\"tap\",\"type\"]},"
            + "\"x\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":5000},"
            + "\"text\":{\"type\":\"string\",\"maxLength\":5},"
            + "\"repeat\":{\"type\":\"integer\",\"default\":1},"
            + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":2},"
            + "\"opts\":{\"type\":\"object\",\"properties\":{\"fast\":{\"type\":\"boolean\"}},\"additionalProperties\":false}"
            + "},\"required\":[\"action\"],\"additionalProperties\":false}";

    private static SchemaValidator.Result v(String args) throws Exception {
        return SchemaValidator.validate(new JSONObject(SCHEMA), new JSONObject(args));
    }

    @Test
    public void validArgumentsPassAndDefaultsApplied() throws Exception {
        SchemaValidator.Result r = v("{\"action\":\"tap\",\"x\":10}");
        assertTrue(r.message(), r.ok());
        assertEquals(1L, r.value.getLong("repeat"));
    }

    @Test
    public void missingRequiredIsRejected() throws Exception {
        SchemaValidator.Result r = v("{\"x\":10}");
        assertFalse(r.ok());
        assertTrue(r.message().contains("action is required"));
    }

    @Test
    public void enumAndRangeViolations() throws Exception {
        assertFalse(v("{\"action\":\"delete\"}").ok());
        assertFalse(v("{\"action\":\"tap\",\"x\":-1}").ok());
        assertFalse(v("{\"action\":\"tap\",\"x\":99999}").ok());
    }

    @Test
    public void typeMismatchIsRejected() throws Exception {
        SchemaValidator.Result r = v("{\"action\":\"tap\",\"x\":\"left\"}");
        assertFalse(r.ok());
        assertTrue(r.message().contains("must be integer"));
    }

    @Test
    public void numericStringsAreCoerced() throws Exception {
        SchemaValidator.Result r = v("{\"action\":\"tap\",\"x\":\"42\"}");
        assertTrue(r.message(), r.ok());
        assertEquals(42L, r.value.getLong("x"));
    }

    @Test
    public void additionalPropertiesRejected() throws Exception {
        assertFalse(v("{\"action\":\"tap\",\"evil\":true}").ok());
        assertFalse(v("{\"action\":\"tap\",\"opts\":{\"fast\":true,\"x\":1}}").ok());
    }

    @Test
    public void stringLengthAndArrayLimits() throws Exception {
        assertFalse(v("{\"action\":\"type\",\"text\":\"too long text\"}").ok());
        assertFalse(v("{\"action\":\"tap\",\"tags\":[\"a\",\"b\",\"c\"]}").ok());
        assertFalse(v("{\"action\":\"tap\",\"tags\":[1]}").ok());
    }

    @Test
    public void schemaSelfCheck() throws Exception {
        assertNull(SchemaValidator.checkSchema(new JSONObject(SCHEMA)));
        assertNotNull(SchemaValidator.checkSchema(new JSONObject("{\"type\":\"string\"}")));
        assertNotNull(SchemaValidator.checkSchema(new JSONObject("{\"type\":\"object\",\"properties\":{},\"required\":[\"a\"]}")));
    }
}
