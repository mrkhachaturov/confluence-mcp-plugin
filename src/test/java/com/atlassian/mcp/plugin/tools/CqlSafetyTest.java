package com.atlassian.mcp.plugin.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CqlSafetyTest {

    @Test
    public void quote_escapesBackslashThenQuote() {
        assertEquals("a\\\"b", CqlSafety.quote("a\"b"));
        assertEquals("a\\\\b", CqlSafety.quote("a\\b"));
        // A backslash-quote sequence must escape the backslash first so the quote stays escaped.
        assertEquals("\\\\\\\"", CqlSafety.quote("\\\""));
    }

    @Test
    public void quote_nullIsEmpty() {
        assertEquals("", CqlSafety.quote(null));
    }

    @Test
    public void spaceToken_acceptsValidKeys() {
        assertTrue(CqlSafety.isValidSpaceToken("DEV"));
        assertTrue(CqlSafety.isValidSpaceToken("~user.name"));
        assertTrue(CqlSafety.isValidSpaceToken("Team-2024"));
    }

    @Test
    public void spaceToken_rejectsInjectionAttempts() {
        assertFalse(CqlSafety.isValidSpaceToken("A\") OR (space=B"));
        assertFalse(CqlSafety.isValidSpaceToken("A B"));
        assertFalse(CqlSafety.isValidSpaceToken("\""));
        assertFalse(CqlSafety.isValidSpaceToken(""));
        assertFalse(CqlSafety.isValidSpaceToken(null));
    }
}
