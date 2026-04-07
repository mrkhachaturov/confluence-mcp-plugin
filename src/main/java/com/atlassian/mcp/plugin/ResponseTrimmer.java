package com.atlassian.mcp.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Set;

/**
 * Transforms Confluence REST API JSON responses to match the upstream mcp-atlassian
 * Python project's to_simplified_dict() output format.
 *
 * The upstream uses Pydantic models with whitelist-based serialization.
 * Since our plugin receives raw Confluence JSON and doesn't have Pydantic models,
 * we approximate by recursively stripping fields the upstream models never include.
 */
public final class ResponseTrimmer {

    private ResponseTrimmer() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Fields to remove recursively from ALL JSON objects.
     * Upstream Pydantic models never serialize these.
     */
    private static final Set<String> STRIP_RECURSIVE = Set.of(
            "self",
            "_links",
            "_expandable",
            "expand",
            "extensions",
            "profilePicture",
            "userKey"          // internal key — username is sufficient for AI
    );

    /**
     * Top-level fields to remove from content responses.
     * Upstream's to_simplified_dict() never includes these.
     */
    private static final Set<String> STRIP_TOP_LEVEL = Set.of(
            "operations",
            "restrictions",
            "metadata",
            "container",       // duplicate of space
            "position"         // internal page ordering (-1)
    );

    /**
     * Trim a JSON response string to match upstream's simplified output.
     * Returns the original string unchanged if it's not valid JSON.
     */
    public static String trim(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = MAPPER.readTree(json);
            trimNode(root, true);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return json;
        }
    }

    private static void trimNode(JsonNode node, boolean isTopLevel) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;

            // Remove blacklisted fields
            Iterator<String> names = obj.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (STRIP_RECURSIVE.contains(name)) {
                    names.remove();
                } else if (isTopLevel && STRIP_TOP_LEVEL.contains(name)) {
                    names.remove();
                }
            }

            // Recurse into remaining children
            obj.fields().forEachRemaining(e -> trimNode(e.getValue(), false));

        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                trimNode(arr.get(i), false);
            }
        }
    }
}
