package com.atlassian.mcp.plugin.rest;

import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.mcp.plugin.tools.ToolRegistry;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServlet;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds (lazily, once) the MCP SDK transport + sync server, holding the configured {@link
 * HttpServlet} for {@link McpTransportFilter} to delegate into.
 */
@Named("mcpBootstrap")
public class McpBootstrap {

  private static final Logger log = LoggerFactory.getLogger(McpBootstrap.class);

  private static final String SERVER_NAME = "confluence-mcp-plugin";
  private static final String SERVER_VERSION = "1.2.3";
  private static final String SERVER_TITLE = "Confluence MCP Server";
  private static final String SERVER_DESCRIPTION =
      "Connect AI agents to Confluence Data Center — 28 tools across pages, comments, "
          + "labels, attachments, spaces, and users.";
  private static final String SERVER_WEBSITE =
      "https://github.com/mrkhachaturov/confluence-mcp-plugin";
  private static final String SERVER_INSTRUCTIONS =
      "This server exposes tools for Confluence Data Center.\n"
          + "- To find content: use `search` with a CQL query.\n"
          + "- To read a page: use `get_page` with a numeric page id or a Confluence page URL.\n"
          + "- To create or edit pages: `create_page`, `update_page`, `append_to_page`, "
          + "`prepend_to_page`, `replace_section` — content is Markdown by default and is "
          + "converted to Confluence storage format (panels, status badges, task lists, TOC, "
          + "expand sections are supported).\n"
          + "- Write tools are hidden when the admin enables read-only mode.";

  private static final String LOGO_DATA_URI = ConfluenceIconConstants.CONFLUENCE_LOGO_DATA_URI;

  private final ToolRegistry toolRegistry;
  private final McpPluginConfig config;
  private final ConfluenceAuthContextExtractor authExtractor;
  private final ApplicationProperties applicationProperties;

  private volatile HttpServletStreamableServerTransportProvider transport;
  private volatile McpSyncServer server;

  @Inject
  public McpBootstrap(
      ToolRegistry toolRegistry,
      McpPluginConfig config,
      ConfluenceAuthContextExtractor authExtractor,
      @ComponentImport ApplicationProperties applicationProperties) {
    this.toolRegistry = toolRegistry;
    this.config = config;
    this.authExtractor = authExtractor;
    this.applicationProperties = applicationProperties;
  }

  public synchronized HttpServlet buildTransport() {
    if (transport != null) {
      return transport;
    }

    ObjectMapper mapper = new ObjectMapper();
    McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);
    JsonSchemaValidator schemaValidator = new DefaultJsonSchemaValidator(mapper);

    var t =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(jsonMapper)
            .mcpEndpoint("/plugins/servlet/mcp")
            .contextExtractor(authExtractor)
            .securityValidator(buildSecurityValidator())
            .build();

    var serverInfo =
        McpSchema.Implementation.builder(SERVER_NAME, SERVER_VERSION)
            .title(SERVER_TITLE)
            .description(SERVER_DESCRIPTION)
            .websiteUrl(SERVER_WEBSITE)
            .icons(
                List.of(
                    McpSchema.Icon.builder(LOGO_DATA_URI)
                        .mimeType("image/svg+xml")
                        .sizes(List.of("any"))
                        .build()))
            .build();

    var s =
        McpServer.sync(t)
            .jsonMapper(jsonMapper)
            .jsonSchemaValidator(schemaValidator)
            .serverInfo(serverInfo)
            .instructions(SERVER_INSTRUCTIONS)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).logging().build())
            .tools(toolRegistry.toSpecifications())
            .build();

    this.transport = t;
    this.server = s;

    log.info("[MCP] SDK transport built ({} tools)", toolRegistry.toSpecifications().size());
    return t;
  }

  private DefaultServerTransportSecurityValidator buildSecurityValidator() {
    var builder =
        DefaultServerTransportSecurityValidator.builder()
            .allowedOrigin("https://claude.ai")
            .allowedOrigin("https://claude.com")
            .allowedOrigin("https://chatgpt.com")
            .allowedOrigin("https://chat.openai.com")
            .allowedOrigin("http://localhost")
            .allowedOrigin("http://localhost:*")
            .allowedOrigin("https://localhost")
            .allowedOrigin("https://localhost:*")
            .allowedOrigin("http://127.0.0.1")
            .allowedOrigin("http://127.0.0.1:*")
            .allowedOrigin("https://127.0.0.1")
            .allowedOrigin("https://127.0.0.1:*")
            .allowedOrigin("http://[::1]")
            .allowedOrigin("http://[::1]:*")
            .allowedOrigin("https://[::1]")
            .allowedOrigin("https://[::1]:*");

    // Host-header allowlist completes the DNS-rebinding defense (the SDK skips Host validation
    // entirely when this list is empty). Pin to the deployment's own host plus loopback for
    // local clients; a missing or mismatched Host now fails closed with 421.
    builder
        .allowedHost("localhost")
        .allowedHost("localhost:*")
        .allowedHost("127.0.0.1")
        .allowedHost("127.0.0.1:*")
        .allowedHost("[::1]")
        .allowedHost("[::1]:*");

    String baseUrl = resolveConfluenceBaseUrl();
    if (baseUrl != null && !baseUrl.isEmpty()) {
      String normalized = normalizeOrigin(baseUrl);
      if (normalized != null) {
        builder.allowedOrigin(normalized);
        builder.allowedOrigin(normalized + ":*");
      }
      String host = hostOf(baseUrl);
      if (host != null) {
        builder.allowedHost(host);
        builder.allowedHost(host + ":*");
      }
    }
    return builder.build();
  }

  private static String hostOf(String url) {
    try {
      String host = URI.create(url).getHost();
      return (host == null || host.isEmpty()) ? null : host;
    } catch (Exception e) {
      return null;
    }
  }

  private String resolveConfluenceBaseUrl() {
    try {
      String override = config.getConfluenceBaseUrlOverride();
      if (override != null && !override.isEmpty()) {
        return override;
      }
      return applicationProperties.getBaseUrl(UrlMode.CANONICAL).toString();
    } catch (Exception e) {
      log.warn("[MCP] could not resolve Confluence base URL for Origin allowlist", e);
      return null;
    }
  }

  private static String normalizeOrigin(String url) {
    try {
      URI u = URI.create(url);
      String scheme = u.getScheme();
      String host = u.getHost();
      int port = u.getPort();
      if (scheme == null || host == null) {
        return null;
      }
      return port == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    } catch (Exception e) {
      return null;
    }
  }

  public synchronized void close() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception e) {
        log.warn("[MCP] error closing SDK server", e);
      }
      server = null;
    }
    transport = null;
  }
}
