package com.atlassian.mcp.plugin.tools;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.mcp.plugin.tools.analytics.*;
import com.atlassian.mcp.plugin.tools.attachments.*;
import com.atlassian.mcp.plugin.tools.comments.*;
import com.atlassian.mcp.plugin.tools.labels.*;
import com.atlassian.mcp.plugin.tools.pages.*;
import com.atlassian.mcp.plugin.tools.spaces.*;
import com.atlassian.mcp.plugin.tools.users.*;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Named
public class ToolRegistry {

    private final Map<String, McpTool> allTools = new ConcurrentHashMap<>();
    private final PluginAccessor pluginAccessor;
    private final McpPluginConfig config;

    @Inject
    public ToolRegistry(
            @ComponentImport PluginAccessor pluginAccessor,
            McpPluginConfig config,
            ConfluenceRestClient client) {
        this.pluginAccessor = pluginAccessor;
        this.config = config;
        registerAllTools(client);
    }

    private void registerAllTools(ConfluenceRestClient client) {
        // Analytics (1)
        register(new GetPageViewsTool(client));

        // Attachments (7)
        register(new DeleteAttachmentTool(client));
        register(new DownloadAttachmentTool(client));
        register(new DownloadContentAttachmentsTool(client));
        register(new GetAttachmentsTool(client));
        register(new GetPageImagesTool(client));
        register(new UploadAttachmentTool(client));
        register(new UploadAttachmentsTool(client));

        // Comments (3)
        register(new AddCommentTool(client));
        register(new GetCommentsTool(client));
        register(new ReplyToCommentTool(client));

        // Labels (2)
        register(new AddLabelTool(client));
        register(new GetLabelsTool(client));

        // Pages (13)
        register(new AppendToPageTool(client));
        register(new ConvertContentTool(client));
        register(new CreatePageTool(client));
        register(new DeletePageTool(client));
        register(new GetPageTool(client));
        register(new GetPageChildrenTool(client));
        register(new GetPageDiffTool(client));
        register(new GetPageHistoryTool(client));
        register(new MovePageTool(client));
        register(new PrependToPageTool(client));
        register(new ReplaceSectionTool(client));
        register(new SearchTool(client));
        register(new UpdatePageTool(client));

        // Spaces (1)
        register(new ListSpacesTool(client));

        // Users (1)
        register(new SearchUserTool(client));
    }

    public void register(McpTool tool) {
        allTools.put(tool.name(), tool);
    }

    /**
     * Returns tools visible to the given user, filtered by:
     * - capability (required plugin installed)
     * - admin disabled list
     * - read-only mode (hides write tools)
     */
    public List<McpTool> listTools(String userKey) {
        return allTools.values().stream()
                .filter(this::isCapabilityMet)
                .filter(t -> config.isToolEnabled(t.name()))
                .filter(t -> !config.isReadOnlyMode() || !t.isWriteTool())
                .collect(Collectors.toList());
    }

    /**
     * Get a tool by name for execution. Returns null if tool doesn't exist.
     */
    public McpTool getTool(String name) {
        return allTools.get(name);
    }

    /**
     * Check if a tool is callable by the given user right now.
     * Returns an error message if not, null if OK.
     */
    public String checkToolAccess(String toolName, String userKey) {
        McpTool tool = allTools.get(toolName);
        if (tool == null) {
            return "Unknown tool: " + toolName;
        }
        if (!isCapabilityMet(tool)) {
            return "Tool '" + toolName + "' requires a Confluence plugin that is not installed on this instance";
        }
        if (!config.isToolEnabled(toolName)) {
            return "Tool '" + toolName + "' is disabled by the administrator";
        }
        if (config.isReadOnlyMode() && tool.isWriteTool()) {
            return "Tool '" + toolName + "' is a write operation and the server is in read-only mode";
        }
        return null;
    }

    private boolean isCapabilityMet(McpTool tool) {
        String requiredPlugin = tool.requiredPluginKey();
        if (requiredPlugin == null) {
            return true;
        }
        return pluginAccessor.isPluginEnabled(requiredPlugin);
    }

    /** Returns all registered tools (unfiltered). Used by admin UI. */
    public Collection<McpTool> getAllTools() {
        return allTools.values();
    }

    public int totalRegistered() {
        return allTools.size();
    }
}
