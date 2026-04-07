package com.atlassian.mcp.plugin;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.sal.api.ApplicationProperties;
import org.junit.Before;
import org.junit.Test;

public class ConfluenceRestClientTest {

    private ApplicationProperties applicationProperties;
    private McpPluginConfig pluginConfig;

    @Before
    public void setUp() {
        applicationProperties = mock(ApplicationProperties.class);
        pluginConfig = mock(McpPluginConfig.class);
        when(applicationProperties.getBaseUrl()).thenReturn("http://localhost:1990/confluence");
        when(pluginConfig.getConfluenceBaseUrlOverride()).thenReturn("");
    }

    @Test
    public void testBaseUrlUsesApplicationProperties() {
        ConfluenceRestClient client = new ConfluenceRestClient(applicationProperties, pluginConfig);
        assertNotNull(client);
    }

    @Test
    public void testBaseUrlOverrideTakesPrecedence() {
        when(pluginConfig.getConfluenceBaseUrlOverride()).thenReturn("http://internal:8080");
        ConfluenceRestClient client = new ConfluenceRestClient(applicationProperties, pluginConfig);
        assertNotNull(client);
    }
}
