package com.atlassian.mcp.plugin.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the SSRF-hardening checks on the {@code confluenceBaseUrl} override. All cases use
 * literal IPs/hosts so no real DNS lookup is required (numeric literals are parsed by {@code
 * InetAddress.getAllByName} without a network round-trip).
 */
public class UrlSafetyTest {

  @Test
  public void syntactic_acceptsHttpsPublicHost() {
    assertTrue(UrlSafety.isSyntacticallySafeBaseUrl("https://confluence.example.com"));
    assertTrue(UrlSafety.isSyntacticallySafeBaseUrl("https://confluence.example.com/"));
  }

  @Test
  public void syntactic_acceptsHttpPrivateHost() {
    // A legitimate internal Confluence base URL is commonly a private (RFC1918) address.
    assertTrue(UrlSafety.isSyntacticallySafeBaseUrl("http://10.0.0.5:8090"));
    assertTrue(UrlSafety.isSyntacticallySafeBaseUrl("http://confluence.internal:8090"));
  }

  @Test
  public void syntactic_rejectsLoopback() {
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("http://localhost:8090"));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("http://127.0.0.1"));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("http://[::1]:8090"));
  }

  @Test
  public void syntactic_rejectsCloudMetadata() {
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("http://169.254.169.254"));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("http://169.254.169.254/latest/meta-data/"));
  }

  @Test
  public void syntactic_rejectsEmbeddedCredentials() {
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("https://user:pass@evil.example.com"));
  }

  @Test
  public void syntactic_rejectsNonHttpSchemes() {
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("file:///etc/passwd"));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("gopher://evil.example/"));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("ftp://evil.example/"));
  }

  @Test
  public void syntactic_rejectsBlankOrMalformed() {
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl(null));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl(""));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("   "));
    assertFalse(UrlSafety.isSyntacticallySafeBaseUrl("not a url"));
  }

  @Test
  public void forSet_acceptsPublicLiteralIp() {
    assertTrue(UrlSafety.isSafeBaseUrlForSet("https://8.8.8.8"));
  }

  @Test
  public void forSet_acceptsPrivateLiteralIp() {
    assertTrue(UrlSafety.isSafeBaseUrlForSet("http://10.1.2.3:8090"));
  }

  @Test
  public void forSet_rejectsLoopbackAndMetadataLiterals() {
    assertFalse(UrlSafety.isSafeBaseUrlForSet("http://127.0.0.1"));
    assertFalse(UrlSafety.isSafeBaseUrlForSet("http://169.254.169.254"));
  }

  @Test
  public void forSet_rejectsLinkLocalViaResolution() {
    assertFalse(UrlSafety.isSafeBaseUrlForSet("http://169.254.0.1"));
  }
}
