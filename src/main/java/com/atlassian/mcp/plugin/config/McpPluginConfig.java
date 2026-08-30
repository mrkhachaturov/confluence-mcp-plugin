package com.atlassian.mcp.plugin.config;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Named
public class McpPluginConfig {

  private static final String PREFIX = "com.atlassian.mcp.plugin.";
  private final PluginSettingsFactory pluginSettingsFactory;

  @Inject
  public McpPluginConfig(@ComponentImport PluginSettingsFactory pluginSettingsFactory) {
    this.pluginSettingsFactory = pluginSettingsFactory;
  }

  private PluginSettings settings() {
    return pluginSettingsFactory.createGlobalSettings();
  }

  public boolean isEnabled() {
    return Boolean.parseBoolean((String) settings().get(PREFIX + "enabled"));
  }

  public void setEnabled(boolean enabled) {
    settings().put(PREFIX + "enabled", String.valueOf(enabled));
  }

  public Set<String> getAllowedUserKeys() {
    String raw = (String) settings().get(PREFIX + "allowedUsers");
    if (raw == null || raw.isBlank()) {
      return Collections.emptySet();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  public void setAllowedUserKeys(String commaDelimited) {
    settings().put(PREFIX + "allowedUsers", commaDelimited);
  }

  public boolean isUserAllowed(String userKey) {
    // Default-DENY: an empty allowlist no longer grants everyone. Blanket access for all
    // authenticated users is now an explicit, opt-in choice (allowAllAuthenticatedUsers),
    // checked by AccessControlFilter — not an accidental side effect of a blank config.
    return getAllowedUserKeys().contains(userKey);
  }

  /**
   * When true, ANY authenticated Confluence user may use the MCP server (the allowlist is ignored).
   * Defaults to false — admins must consciously opt into open access. This replaces the old footgun
   * where a blank allowedUsers + allowedGroups silently meant "allow all".
   */
  public boolean isAllowAllAuthenticatedUsers() {
    return Boolean.parseBoolean((String) settings().get(PREFIX + "allowAllAuthenticatedUsers"));
  }

  public void setAllowAllAuthenticatedUsers(boolean allowAll) {
    settings().put(PREFIX + "allowAllAuthenticatedUsers", String.valueOf(allowAll));
  }

  public Set<String> getAllowedGroups() {
    String raw = (String) settings().get(PREFIX + "allowedGroups");
    if (raw == null || raw.isBlank()) {
      return Collections.emptySet();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  public void setAllowedGroups(String commaDelimited) {
    settings().put(PREFIX + "allowedGroups", commaDelimited);
  }

  public Set<String> getDisabledTools() {
    String raw = (String) settings().get(PREFIX + "disabledTools");
    if (raw == null || raw.isBlank()) {
      return Collections.emptySet();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  public void setDisabledTools(String commaDelimited) {
    settings().put(PREFIX + "disabledTools", commaDelimited);
  }

  public boolean isToolEnabled(String toolName) {
    return !getDisabledTools().contains(toolName);
  }

  public boolean isReadOnlyMode() {
    return Boolean.parseBoolean((String) settings().get(PREFIX + "readOnlyMode"));
  }

  public void setReadOnlyMode(boolean readOnly) {
    settings().put(PREFIX + "readOnlyMode", String.valueOf(readOnly));
  }

  public String getConfluenceBaseUrlOverride() {
    String val = (String) settings().get(PREFIX + "confluenceBaseUrl");
    return val == null ? "" : val;
  }

  public void setConfluenceBaseUrlOverride(String url) {
    settings().put(PREFIX + "confluenceBaseUrl", url);
  }

  // OAuth 2.0 configuration
  public String getOAuthClientId() {
    String val = (String) settings().get(PREFIX + "oauthClientId");
    return val == null ? "" : val;
  }

  public void setOAuthClientId(String clientId) {
    settings().put(PREFIX + "oauthClientId", clientId);
  }

  public String getOAuthClientSecret() {
    String val = (String) settings().get(PREFIX + "oauthClientSecret");
    return val == null ? "" : val;
  }

  public void setOAuthClientSecret(String secret) {
    settings().put(PREFIX + "oauthClientSecret", secret);
  }

  public boolean isOAuthEnabled() {
    String id = getOAuthClientId();
    String secret = getOAuthClientSecret();
    return id != null && !id.isEmpty() && secret != null && !secret.isEmpty();
  }
}
