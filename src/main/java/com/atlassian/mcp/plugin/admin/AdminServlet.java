package com.atlassian.mcp.plugin.admin;

import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.templaterenderer.TemplateRenderer;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;

public class AdminServlet extends HttpServlet {

    private final LoginUriProvider loginUriProvider;
    private final TemplateRenderer renderer;
    private final PermissionManager permissionManager;

    @Inject
    public AdminServlet(
            @ComponentImport LoginUriProvider loginUriProvider,
            @ComponentImport TemplateRenderer renderer,
            @ComponentImport PermissionManager permissionManager) {
        this.loginUriProvider = loginUriProvider;
        this.renderer = renderer;
        this.permissionManager = permissionManager;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        if (user == null) {
            resp.sendRedirect(loginUriProvider.getLoginUri(getUri(req)).toASCIIString());
            return;
        }
        if (!permissionManager.isConfluenceAdministrator(user)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Confluence admin access required");
            return;
        }
        resp.setContentType("text/html;charset=utf-8");
        renderer.render("templates/admin.vm", resp.getWriter());
    }

    private URI getUri(HttpServletRequest req) {
        StringBuffer buf = req.getRequestURL();
        if (req.getQueryString() != null) {
            buf.append("?").append(req.getQueryString());
        }
        return URI.create(buf.toString());
    }
}
