package com.atlassian.mcp.plugin.rest;

import com.atlassian.annotations.security.UnrestrictedAccess;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter-based transport for the MCP SDK servlet. Atlassian {@code <servlet>} modules
 * hardcode {@code asyncSupported=false}; {@code <servlet-filter>} modules honor the JVM flag
 * {@code atlassian.plugins.filter.async.default=true}. This filter owns
 * {@code /plugins/servlet/mcp}, calls the SDK transport servlet's {@code service(...)}
 * directly, and never invokes {@code chain.doFilter()} — it IS the endpoint.
 */
@UnrestrictedAccess
@Named("mcpTransportFilter")
public class McpTransportFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpTransportFilter.class);

    private final McpBootstrap bootstrap;
    private volatile HttpServlet delegate;

    @Inject
    public McpTransportFilter(McpBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        HttpServlet d = bootstrap.buildTransport();
        d.init(new FilterBackedServletConfig(filterConfig));
        this.delegate = d;
        log.info("[MCP] McpTransportFilter initialized — delegate={}", d.getClass().getName());
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServlet d = delegate;
        if (d == null) {
            throw new ServletException("McpTransportFilter not initialized");
        }
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;
        if (log.isDebugEnabled()) {
            log.debug("[MCP] doFilter uri={} dispatcher={} asyncSupported={}",
                    httpReq.getRequestURI(), httpReq.getDispatcherType(), httpReq.isAsyncSupported());
        }
        d.service(httpReq, httpResp);
    }

    @Override
    public void destroy() {
        HttpServlet d = delegate;
        if (d != null) {
            try {
                d.destroy();
            } catch (Exception e) {
                log.warn("[MCP] error destroying SDK transport", e);
            }
        }
        bootstrap.close();
        delegate = null;
    }

    private static final class FilterBackedServletConfig implements ServletConfig {
        private final FilterConfig filterConfig;

        FilterBackedServletConfig(FilterConfig filterConfig) {
            this.filterConfig = filterConfig;
        }

        @Override public String getServletName() { return "mcp-transport"; }
        @Override public ServletContext getServletContext() { return filterConfig.getServletContext(); }
        @Override public String getInitParameter(String name) { return filterConfig.getInitParameter(name); }
        @Override public Enumeration<String> getInitParameterNames() { return filterConfig.getInitParameterNames(); }
    }
}
