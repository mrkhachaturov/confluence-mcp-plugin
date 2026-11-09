package com.atlassian.mcp.plugin.rest;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class McpBootstrapVersionTest {

  /** serverInfo.version comes from the build, so it can no longer fall behind a release. */
  @Test
  public void serverVersionIsTheBuiltVersion() {
    String version = McpBootstrap.SERVER_VERSION;
    assertTrue("not filled in by the build: " + version, version.matches("\\d+\\.\\d+\\.\\d+.*"));
  }
}
