package com.atlassian.mcp.plugin;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

@Named
public class ConfluenceRestClient {

    private final ApplicationProperties applicationProperties;
    private final com.atlassian.mcp.plugin.config.McpPluginConfig pluginConfig;
    private final HttpClient httpClient;

    @Inject
    public ConfluenceRestClient(
            @ComponentImport ApplicationProperties applicationProperties,
            com.atlassian.mcp.plugin.config.McpPluginConfig pluginConfig) {
        this.applicationProperties = applicationProperties;
        this.pluginConfig = pluginConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String get(String path, String authHeader) throws McpToolException {
        return execute(buildRequest(path, authHeader, "GET", null));
    }

    public String post(String path, String body, String authHeader) throws McpToolException {
        return execute(buildRequest(path, authHeader, "POST", body));
    }

    public String put(String path, String body, String authHeader) throws McpToolException {
        return execute(buildRequest(path, authHeader, "PUT", body));
    }

    public String delete(String path, String authHeader) throws McpToolException {
        return execute(buildRequest(path, authHeader, "DELETE", null));
    }

    /**
     * Upload a file as multipart/form-data (for attachments).
     * Confluence requires X-Atlassian-Token: nocheck for attachment uploads.
     */
    public String postMultipart(String path, Path filePath, String comment, boolean minorEdit,
                                String authHeader) throws McpToolException {
        try {
            String boundary = "----McpBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] fileBytes = Files.readAllBytes(filePath);
            String fileName = filePath.getFileName().toString();
            String mimeType = Files.probeContentType(filePath);
            if (mimeType == null) mimeType = "application/octet-stream";

            // Build multipart body
            StringBuilder prefix = new StringBuilder();
            prefix.append("--").append(boundary).append("\r\n");
            prefix.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(fileName).append("\"\r\n");
            prefix.append("Content-Type: ").append(mimeType).append("\r\n\r\n");

            StringBuilder suffix = new StringBuilder();
            suffix.append("\r\n");
            if (comment != null && !comment.isBlank()) {
                suffix.append("--").append(boundary).append("\r\n");
                suffix.append("Content-Disposition: form-data; name=\"comment\"\r\n\r\n");
                suffix.append(comment).append("\r\n");
            }
            if (minorEdit) {
                suffix.append("--").append(boundary).append("\r\n");
                suffix.append("Content-Disposition: form-data; name=\"minorEdit\"\r\n\r\n");
                suffix.append("true").append("\r\n");
            }
            suffix.append("--").append(boundary).append("--\r\n");

            byte[] prefixBytes = prefix.toString().getBytes(StandardCharsets.UTF_8);
            byte[] suffixBytes = suffix.toString().getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[prefixBytes.length + fileBytes.length + suffixBytes.length];
            System.arraycopy(prefixBytes, 0, body, 0, prefixBytes.length);
            System.arraycopy(fileBytes, 0, body, prefixBytes.length, fileBytes.length);
            System.arraycopy(suffixBytes, 0, body, prefixBytes.length + fileBytes.length, suffixBytes.length);

            String url = getBaseUrl() + path;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-Atlassian-Token", "nocheck")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (authHeader != null && !authHeader.isBlank()) {
                builder.header("Authorization", authHeader);
            }

            return execute(builder.build());
        } catch (McpToolException e) {
            throw e;
        } catch (IOException e) {
            throw new McpToolException("Failed to read file: " + e.getMessage(), e);
        }
    }

    /**
     * Download binary content from a URL path. Returns raw bytes.
     */
    public byte[] getBytes(String path, String authHeader) throws McpToolException {
        String url = getBaseUrl() + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET();

        if (authHeader != null && !authHeader.isBlank()) {
            builder.header("Authorization", authHeader);
        }

        try {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new McpToolException("Confluence API returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new McpToolException("Failed to download: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Download interrupted", e);
        }
    }

    private String getBaseUrl() {
        String override = pluginConfig.getConfluenceBaseUrlOverride();
        if (override != null && !override.isBlank()) {
            return override.replaceAll("/+$", "");
        }
        return applicationProperties.getBaseUrl().replaceAll("/+$", "");
    }

    private HttpRequest buildRequest(String path, String authHeader, String method, String body) {
        String url = getBaseUrl() + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (authHeader != null && !authHeader.isBlank()) {
            builder.header("Authorization", authHeader);
        }

        if (body != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    private String execute(HttpRequest request) throws McpToolException {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new McpToolException(
                        "Confluence API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            // Trim response to match upstream mcp-atlassian's to_simplified_dict() output
            return ResponseTrimmer.trim(response.body());
        } catch (IOException e) {
            throw new McpToolException("Failed to connect to Confluence REST API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Confluence REST API call interrupted", e);
        }
    }
}
