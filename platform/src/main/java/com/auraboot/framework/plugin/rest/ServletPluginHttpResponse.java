package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/** Adapts a servlet response to the neutral {@link PluginHttpResponse}. */
public class ServletPluginHttpResponse implements PluginHttpResponse {

    private final HttpServletResponse res;

    public ServletPluginHttpResponse(HttpServletResponse res) {
        this.res = res;
    }

    @Override
    public PluginHttpResponse status(int code) {
        res.setStatus(code);
        return this;
    }

    @Override
    public PluginHttpResponse header(String name, String value) {
        res.setHeader(name, value);
        return this;
    }

    @Override
    public PluginHttpResponse contentType(String mediaType) {
        res.setContentType(mediaType);
        return this;
    }

    @Override
    public OutputStream out() {
        try {
            return res.getOutputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
