package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts a servlet request to the neutral {@link PluginHttpRequest}. */
public class ServletPluginHttpRequest implements PluginHttpRequest {

    private final HttpServletRequest req;
    private final Map<String, String> pathVars;
    private byte[] cachedBody;

    public ServletPluginHttpRequest(HttpServletRequest req, Map<String, String> pathVars) {
        this.req = req;
        this.pathVars = pathVars;
    }

    @Override public String method() { return req.getMethod(); }

    @Override public String path() { return req.getRequestURI(); }

    @Override public Map<String, String> pathVars() { return pathVars; }

    @Override
    public Map<String, List<String>> query() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        req.getParameterMap().forEach((k, v) -> out.put(k, Arrays.asList(v)));
        return out;
    }

    @Override public String header(String name) { return req.getHeader(name); }

    @Override
    public byte[] body() {
        if (cachedBody == null) {
            try {
                cachedBody = req.getInputStream().readAllBytes();
            } catch (IOException e) {
                cachedBody = new byte[0];
            }
        }
        return cachedBody;
    }
}
