package com.atlassian.mcp.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Transforms raw Confluence API JSON responses into the simplified format
 * that upstream mcp-atlassian returns via its Pydantic models + to_simplified_dict().
 *
 * Each method mirrors a specific upstream model's transformation.
 */
public final class ResponseTransformer {

    private ResponseTransformer() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Build a full page URL for Server/DC.
     * Mirrors upstream: f"{base_url}/pages/viewpage.action?pageId={page_id}"
     */
    public static String pageUrl(String baseUrl, String pageId) {
        if (baseUrl == null || pageId == null) return null;
        return baseUrl.replaceAll("/+$", "") + "/pages/viewpage.action?pageId=" + pageId;
    }

    /**
     * Transform a raw Confluence page JSON into upstream's ConfluencePage.to_simplified_dict() format.
     *
     * Input:  raw Confluence REST API /rest/api/content/{id} response (after ResponseTrimmer)
     * Output: {id, title, type, created, updated, url, space, author, version, attachments, content, ancestors}
     */
    public static String simplifyPage(String rawJson, String baseUrl, boolean convertToMarkdown) {
        try {
            JsonNode raw = MAPPER.readTree(rawJson);
            ObjectNode result = simplifyPageNode(raw, baseUrl, convertToMarkdown);
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return rawJson; // fallback to raw if transformation fails
        }
    }

    /**
     * Transform a raw page JSON node into a simplified page object.
     */
    public static ObjectNode simplifyPageNode(JsonNode raw, String baseUrl, boolean convertToMarkdown) {
        ObjectNode result = MAPPER.createObjectNode();

        result.put("id", raw.path("id").asText(""));
        result.put("title", raw.path("title").asText(""));
        result.put("type", raw.path("type").asText("page"));

        // Timestamps from history or version
        JsonNode history = raw.path("history");
        if (history.has("createdDate")) {
            result.put("created", formatTimestamp(history.path("createdDate").asText("")));
        }
        JsonNode version = raw.path("version");
        if (version.has("when")) {
            result.put("updated", formatTimestamp(version.path("when").asText("")));
        }

        // URL — mirrors upstream Server/DC format
        String id = raw.path("id").asText(null);
        String url = pageUrl(baseUrl, id);
        if (url != null) {
            result.put("url", url);
        }

        // Space
        JsonNode space = raw.path("space");
        if (space.has("key")) {
            ObjectNode spaceNode = MAPPER.createObjectNode();
            spaceNode.put("key", space.path("key").asText(""));
            spaceNode.put("name", space.path("name").asText(""));
            result.set("space", spaceNode);
        }

        // Author — from version.by
        if (version.has("by")) {
            result.put("author", version.path("by").path("displayName").asText(""));
        }

        // Version number
        if (version.has("number")) {
            result.put("version", version.path("number").asInt());
        }

        // Attachments
        ArrayNode attachments = MAPPER.createArrayNode();
        JsonNode attsNode = raw.path("children").path("attachment").path("results");
        if (attsNode.isArray()) {
            for (JsonNode att : attsNode) {
                attachments.add(simplifyAttachmentNode(att));
            }
        }
        result.set("attachments", attachments);

        // Content — convert storage to markdown if requested
        JsonNode bodyStorage = raw.path("body").path("storage");
        if (bodyStorage.has("value")) {
            String storageValue = bodyStorage.path("value").asText("");
            ObjectNode contentNode = MAPPER.createObjectNode();
            if (convertToMarkdown && !storageValue.isEmpty()) {
                contentNode.put("value", StorageToMarkdown.convert(storageValue));
                contentNode.put("format", "markdown");
            } else {
                contentNode.put("value", storageValue);
                contentNode.put("format", "storage");
            }
            result.set("content", contentNode);
        }

        // Ancestors
        JsonNode ancestors = raw.path("ancestors");
        if (ancestors.isArray() && ancestors.size() > 0) {
            ArrayNode ancestorArray = MAPPER.createArrayNode();
            for (JsonNode a : ancestors) {
                if (a.has("id")) {
                    ObjectNode anc = MAPPER.createObjectNode();
                    anc.put("id", a.path("id").asText(""));
                    anc.put("title", a.path("title").asText(""));
                    ancestorArray.add(anc);
                }
            }
            result.set("ancestors", ancestorArray);
        }

        // Labels (from metadata.labels if present)
        JsonNode labels = raw.path("metadata").path("labels").path("results");
        if (labels.isArray() && labels.size() > 0) {
            ArrayNode labelArray = MAPPER.createArrayNode();
            for (JsonNode l : labels) {
                labelArray.add(simplifyLabelNode(l));
            }
            result.set("labels", labelArray);
        }

        return result;
    }

    /**
     * Transform a raw Confluence comment JSON into upstream's ConfluenceComment.to_simplified_dict() format.
     *
     * Output: {id, body, created, updated, author, parent_comment_id, location}
     */
    public static ObjectNode simplifyCommentNode(JsonNode raw, boolean convertToMarkdown) {
        ObjectNode result = MAPPER.createObjectNode();

        result.put("id", raw.path("id").asText(""));

        // Comment body — upstream uses body.view (rendered HTML), fallback to body.storage
        JsonNode bodyView = raw.path("body").path("view");
        JsonNode bodyStorage = raw.path("body").path("storage");
        String bodyHtml = "";
        if (bodyView.has("value")) {
            bodyHtml = bodyView.path("value").asText("");
        } else if (bodyStorage.has("value")) {
            bodyHtml = bodyStorage.path("value").asText("");
        }
        if (convertToMarkdown && !bodyHtml.isEmpty()) {
            result.put("body", StorageToMarkdown.convert(bodyHtml));
        } else {
            result.put("body", bodyHtml);
        }

        // Timestamps
        JsonNode version = raw.path("version");
        if (version.has("when")) {
            result.put("updated", formatTimestamp(version.path("when").asText("")));
        }
        // Created from version.by or direct field
        if (version.has("when") && version.path("number").asInt(0) == 1) {
            result.put("created", formatTimestamp(version.path("when").asText("")));
        }

        // Author
        if (version.has("by")) {
            result.put("author", version.path("by").path("displayName").asText(""));
        }

        // Parent comment ID — from ancestors
        JsonNode ancestors = raw.path("ancestors");
        if (ancestors.isArray() && ancestors.size() > 0) {
            JsonNode lastAncestor = ancestors.get(ancestors.size() - 1);
            result.put("parent_comment_id", lastAncestor.path("id").asText(""));
        } else {
            result.putNull("parent_comment_id");
        }

        // Location
        result.put("location", "footer");

        return result;
    }

    /**
     * Transform a raw Confluence label JSON into upstream's ConfluenceLabel.to_simplified_dict() format.
     *
     * Output: {id, name, prefix, label}
     */
    public static ObjectNode simplifyLabelNode(JsonNode raw) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("id", raw.path("id").asText(""));
        result.put("name", raw.path("name").asText(""));
        result.put("prefix", raw.path("prefix").asText("global"));
        result.put("label", raw.path("label").asText(raw.path("name").asText("")));
        return result;
    }

    /**
     * Transform a raw Confluence user search result into upstream's format.
     *
     * Output: {display_name, email, profile_picture}
     */
    public static ObjectNode simplifyUserNode(JsonNode raw) {
        ObjectNode result = MAPPER.createObjectNode();
        // User data can be in "user" sub-object (from search results) or direct
        JsonNode user = raw.has("user") ? raw.path("user") : raw;
        result.put("display_name", user.path("displayName").asText(""));
        result.put("email", user.path("email").asText(""));
        return result;
    }

    /**
     * Transform a raw Confluence attachment JSON into simplified format.
     *
     * Output: {id, type, status, title, media_type, file_size, download_url}
     */
    public static ObjectNode simplifyAttachmentNode(JsonNode raw) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("id", raw.path("id").asText(""));
        result.put("type", "attachment");
        result.put("status", raw.path("status").asText("current"));
        result.put("title", raw.path("title").asText(""));
        result.put("media_type", raw.path("extensions").path("mediaType").asText(""));
        result.put("file_size", raw.path("extensions").path("fileSize").asLong(0));

        // Download URL — may be in _links.download (if not already stripped by ResponseTrimmer)
        JsonNode links = raw.path("_links");
        if (links.has("download")) {
            result.put("download_url", links.path("download").asText(""));
        }
        return result;
    }

    /** Convenience: parse JSON string to JsonNode. */
    public static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /** Convenience: serialize object to JSON string. */
    public static String toJson(Object obj) throws Exception {
        return MAPPER.writeValueAsString(obj);
    }

    /**
     * Format a timestamp to "YYYY-MM-DD HH:MM:SS" matching upstream's TimestampMixin.
     * Handles ISO 8601 variants: Z, +0000, +00:00, +03:00, etc.
     * Returns original string if parsing fails.
     */
    public static String formatTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return "";
        try {
            // Normalize timezone format: +0300 → +03:00
            String normalized = timestamp.replace("Z", "+00:00");
            if (normalized.contains("+") && !normalized.endsWith("Z")) {
                int plusPos = normalized.lastIndexOf('+');
                String tzPart = normalized.substring(plusPos);
                if (tzPart.length() == 5 && !tzPart.contains(":")) {
                    normalized = normalized.substring(0, plusPos) + tzPart.substring(0, 3) + ":" + tzPart.substring(3);
                }
            }
            OffsetDateTime dt = OffsetDateTime.parse(normalized);
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException e) {
            return timestamp; // Return original if parsing fails
        }
    }
}
