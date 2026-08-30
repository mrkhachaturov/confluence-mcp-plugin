package com.atlassian.mcp.plugin.tools;

/**
 * Escaping/validation for values interpolated into CQL grammar.
 *
 * <p>URL-encoding the final query string prevents HTTP-level injection but does NOT protect the CQL
 * grammar itself: a value placed inside a {@code "..."} literal can still break out with an
 * unescaped quote/backslash, and an unvalidated space-key token can inject whole CQL clauses (e.g.
 * {@code A") OR (space=B}). These helpers close that gap.
 */
public final class CqlSafety {

  private CqlSafety() {}

  /** Escape a string for use inside a CQL double-quoted literal: backslash first, then quote. */
  public static String quote(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /**
   * Conservative space-key token: letters, digits, underscore, tilde (personal-space keys start
   * with {@code ~}), hyphen, dot. Anything else is rejected so {@code spaces_filter} cannot inject
   * CQL clauses.
   */
  public static boolean isValidSpaceToken(String token) {
    return token != null && token.matches("[A-Za-z0-9_~.-]+");
  }
}
