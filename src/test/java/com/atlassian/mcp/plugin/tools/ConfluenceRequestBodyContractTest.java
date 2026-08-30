package com.atlassian.mcp.plugin.tools;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Validates the JSON every write tool builds against Confluence's own OpenAPI description.
 *
 * <p>The plugin talks to Confluence over REST rather than the in-process Java API, so nothing stops
 * a tool from serialising its MCP parameter names straight into the request body. Confluence
 * answers such a call with a 400 that names no field, and without this check the build notices
 * nothing.
 *
 * <p>The document is Atlassian's published Data Center description, vendored verbatim. It is
 * incomplete: it describes no {@code PUT /rest/api/content/{id}} and no {@code /move/} endpoint,
 * though both work on a live instance. A call the document does not describe cannot be judged, so
 * {@link #UNDESCRIBED} names those tools explicitly rather than letting them pass as if they had
 * been checked.
 */
public class ConfluenceRequestBodyContractTest {

  private static final String SPEC = "/confluence-openapi.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Write tools whose endpoint Atlassian's document does not describe. Every one of them issues a
   * PUT, which the document omits entirely. Shrinking this list is the point of keeping it.
   */
  private static final Set<String> UNDESCRIBED =
      Set.of("update_page", "move_page", "append_to_page", "prepend_to_page", "replace_section");

  /**
   * Write tools that carry no JSON body at all, so there is nothing here to judge: two issue a
   * DELETE and two upload multipart form data.
   */
  private static final Set<String> BODYLESS =
      Set.of("delete_page", "delete_attachment", "upload_attachment", "upload_attachments");

  /**
   * A generic sample fills every declared parameter, which pins a tool to whichever branch that
   * combination selects. These arguments reach a branch the full sample cannot: replace_section
   * needs a heading that exists in the page the mock returns.
   */
  private static final Map<String, Map<String, Object>> OVERRIDES =
      Map.of(
          "replace_section",
          Map.of("page_id", "123456789", "heading", "test", "content", "replacement"));

  private static OpenApiInteractionValidator validator;
  private static JsonNode specification;

  @BeforeClass
  public static void loadSpec() throws Exception {
    JsonNode spec;
    try (InputStream in = ConfluenceRequestBodyContractTest.class.getResourceAsStream(SPEC)) {
      assertNotNull("Confluence OpenAPI spec missing from test resources", in);
      spec = MAPPER.readTree(in);
    }
    forbidUnknownProperties(spec);
    makeOperationIdsUnique(spec);
    specification = spec;
    validator =
        OpenApiInteractionValidator.createForInlineApiSpecification(MAPPER.writeValueAsString(spec))
            .build();
  }

  /**
   * JSON Schema permits unknown properties unless a schema forbids them, so a faithful reading of
   * the document accepts bodies the server rejects. Confluence refuses a content body carrying a
   * field it does not know, so the document is tightened here to describe how the server behaves.
   */
  private static void forbidUnknownProperties(JsonNode node) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      // Only a node typed "object" is a schema; a bare properties map is not.
      if ("object".equals(object.path("type").asText())
          && object.path("properties").isObject()
          && !object.has("additionalProperties")) {
        object.put("additionalProperties", false);
      }
      object.properties().forEach(field -> forbidUnknownProperties(field.getValue()));
    } else if (node.isArray()) {
      node.forEach(ConfluenceRequestBodyContractTest::forbidUnknownProperties);
    }
  }

  /** The published document repeats operationIds, which the loader treats as fatal. */
  private static void makeOperationIdsUnique(JsonNode spec) {
    Set<String> seen = new HashSet<>();
    JsonNode paths = spec.path("paths");
    paths
        .fieldNames()
        .forEachRemaining(
            path ->
                paths
                    .path(path)
                    .properties()
                    .forEach(
                        operation -> {
                          JsonNode node = operation.getValue();
                          if (!node.isObject() || !node.has("operationId")) return;
                          String id = node.get("operationId").asText();
                          for (int n = 2; !seen.add(id); n++) {
                            id = node.get("operationId").asText() + "_" + n;
                          }
                          ((ObjectNode) node).put("operationId", id);
                        }));
  }

  @Test
  public void everyWriteToolSendsABodyConfluenceAccepts() throws Exception {
    List<String> violations = new ArrayList<>();
    List<String> silent = new ArrayList<>();
    Set<String> unjudged = new TreeSet<>();

    for (Class<?> type : ToolScan.mcpToolClasses()) {
      ConfluenceRestClient client = mockClient();
      McpTool tool = instantiate(type, client);
      if (!tool.isWriteTool()) continue;

      List<Sent> sent = capture(tool, client);
      if (sent.isEmpty()) {
        if (!BODYLESS.contains(tool.name())) silent.add(tool.name());
        continue;
      }

      boolean judged = false;
      for (Sent one : sent) {
        if (!describes(one)) continue;
        judged = true;
        for (String problem : validate(one)) {
          violations.add(tool.name() + ": " + one.method + " " + one.path + " — " + problem);
        }
      }
      if (!judged) unjudged.add(tool.name());
    }

    assertEquals(
        "these write tools never reached Confluence, so this check covers none of them",
        List.of(),
        silent);
    assertEquals(
        "these tools build a body Confluence rejects, so the call does nothing",
        List.of(),
        violations);
    assertEquals(
        "the set of tools Atlassian's document cannot judge changed; update UNDESCRIBED",
        new TreeSet<>(UNDESCRIBED),
        unjudged);
  }

  /**
   * True when the document declares a request body for this method on this path.
   *
   * <p>The validator is not asked this question. Handed {@code PUT /rest/api/content/{id}}, which
   * the document describes only for GET and DELETE, it answers with a body finding anyway — it
   * falls back to another operation on the same path, so the finding is about a schema the call
   * never touches. The document is therefore consulted directly.
   */
  private static boolean describes(Sent sent) {
    JsonNode paths = specification.path("paths");
    Iterator<String> templates = paths.fieldNames();
    while (templates.hasNext()) {
      String template = templates.next();
      if (!matches(template, sent.path)) continue;
      JsonNode operation = paths.path(template).path(sent.method.toLowerCase(Locale.ROOT));
      return operation.isObject() && operation.has("requestBody");
    }
    return false;
  }

  /** A path template matches when every {placeholder} stands in for exactly one segment. */
  private static boolean matches(String template, String path) {
    String[] expected = template.split("/", -1);
    String[] actual = path.split("/", -1);
    if (expected.length != actual.length) return false;
    for (int i = 0; i < expected.length; i++) {
      boolean placeholder = expected[i].startsWith("{") && expected[i].endsWith("}");
      if (!placeholder && !expected[i].equals(actual[i])) return false;
    }
    return true;
  }

  private static List<String> validate(Sent sent) {
    ValidationReport report = validator.validateRequest(request(sent));

    List<String> problems = new ArrayList<>();
    for (ValidationReport.Message message : report.getMessages()) {
      // Path, query and header findings belong to the caller, not to the body under test.
      if (!message.getKey().startsWith("validation.request.body")) continue;
      problems.add(message.getMessage().replaceAll("\\s+", " ").trim());
    }
    return problems;
  }

  private static com.atlassian.oai.validator.model.Request request(Sent sent) {
    SimpleRequest.Builder builder =
        "PUT".equals(sent.method)
            ? SimpleRequest.Builder.put(sent.path)
            : SimpleRequest.Builder.post(sent.path);
    return builder.withContentType("application/json").withBody(sent.body).build();
  }

  private record Sent(String method, String path, String body) {}

  /** Drives a tool with synthetic arguments and returns each POST/PUT body it produced. */
  private static List<Sent> capture(McpTool tool, ConfluenceRestClient client) throws Exception {
    Map<String, Object> args =
        OVERRIDES.getOrDefault(tool.name(), sampleObject(tool.inputSchema()));
    try {
      tool.execute(args, "Bearer test");
    } catch (McpToolException | RuntimeException e) {
      // Synthetic arguments may be rejected; whatever was sent before that still counts.
    }

    List<Sent> sent = new ArrayList<>();
    collectPost(client, sent);
    collectPut(client, sent);
    return sent;
  }

  private static void collectPost(ConfluenceRestClient client, List<Sent> out) throws Exception {
    ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(client, atLeast(0)).post(path.capture(), body.capture(), any());
    add("POST", path, body, out);

    path = ArgumentCaptor.forClass(String.class);
    body = ArgumentCaptor.forClass(String.class);
    verify(client, atLeast(0)).postRaw(path.capture(), body.capture(), any());
    add("POST", path, body, out);
  }

  private static void collectPut(ConfluenceRestClient client, List<Sent> out) throws Exception {
    ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(client, atLeast(0)).put(path.capture(), body.capture(), any());
    add("PUT", path, body, out);

    path = ArgumentCaptor.forClass(String.class);
    body = ArgumentCaptor.forClass(String.class);
    verify(client, atLeast(0)).putRaw(path.capture(), body.capture(), any());
    add("PUT", path, body, out);
  }

  private static void add(
      String method, ArgumentCaptor<String> path, ArgumentCaptor<String> body, List<Sent> out) {
    for (int i = 0; i < path.getAllValues().size(); i++) {
      String raw = body.getAllValues().get(i);
      if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) continue;
      out.add(new Sent(method, path.getAllValues().get(i).split("\\?")[0], raw));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sampleObject(Map<String, Object> schema) {
    Map<String, Object> properties =
        (Map<String, Object>) schema.getOrDefault("properties", Map.of());
    Map<String, Object> args = new LinkedHashMap<>();
    properties.forEach(
        (name, property) -> args.put(name, sampleFor(name, (Map<String, Object>) property)));
    return args;
  }

  @SuppressWarnings("unchecked")
  private static Object sampleFor(String name, Map<String, Object> property) {
    List<String> allowed = (List<String>) property.get("enum");
    if (allowed != null && !allowed.isEmpty()) return allowed.get(0);

    switch (String.valueOf(property.get("type"))) {
      case "integer":
      case "number":
        return 1;
      case "boolean":
        return Boolean.FALSE;
      case "array":
        return List.of(sampleFor(name, (Map<String, Object>) property.get("items")));
      case "object":
        return property.containsKey("properties") ? sampleObject(property) : Map.of();
      default:
        if (name.endsWith("_id")) return "123456789";
        if (name.equals("space_key")) return "TEST";
        return "test";
    }
  }

  private static ConfluenceRestClient mockClient() throws Exception {
    ConfluenceRestClient client = mock(ConfluenceRestClient.class);
    when(client.getBaseUrl()).thenReturn("https://confluence.test");
    // A page the section tools can find their heading in; an empty body would stop them short of
    // the write this test exists to inspect.
    String page =
        "{\"id\":\"1\",\"type\":\"page\",\"title\":\"test\",\"version\":{\"number\":1},"
            + "\"body\":{\"storage\":{\"value\":\"<h2>test</h2><p>body</p>\"}}}";
    when(client.get(anyString(), any())).thenReturn(page);
    when(client.getRaw(anyString(), any())).thenReturn(page);
    when(client.post(anyString(), anyString(), any())).thenReturn("{\"id\":\"1\"}");
    when(client.postRaw(anyString(), anyString(), any())).thenReturn("{\"id\":\"1\"}");
    when(client.put(anyString(), anyString(), any())).thenReturn("{\"id\":\"1\"}");
    when(client.putRaw(anyString(), anyString(), any())).thenReturn("{\"id\":\"1\"}");
    return client;
  }

  private static McpTool instantiate(Class<?> type, ConfluenceRestClient client) throws Exception {
    Constructor<?> best = null;
    for (Constructor<?> candidate : type.getConstructors()) {
      if (best == null || candidate.getParameterCount() < best.getParameterCount()) {
        best = candidate;
      }
    }
    Object[] arguments = new Object[best.getParameterCount()];
    Class<?>[] types = best.getParameterTypes();
    for (int i = 0; i < arguments.length; i++) {
      arguments[i] = types[i] == ConfluenceRestClient.class ? client : mock(types[i]);
    }
    return (McpTool) best.newInstance(arguments);
  }
}
