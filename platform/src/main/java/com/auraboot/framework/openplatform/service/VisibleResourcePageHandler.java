package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.PublicApiHandlerExtension;
import org.springframework.stereotype.Component;
import java.util.*;

/** A reusable no-target example: counts the authorized page, without claiming a full inventory count. */
@Component
public class VisibleResourcePageHandler implements PublicApiHandlerExtension {
    @Override public Definition definition() {
        return new Definition("core.visible-page", "Visible resource page summary", "1",
                Map.of("limit", ValueType.INTEGER), Set.of("limit"),
                Map.of("countOnPage", ValueType.INTEGER, "hasMore", ValueType.BOOLEAN));
    }
    @Override public Map<String, Object> execute(Map<String, Object> parameters, ResourceReader resource) {
        int limit = new java.math.BigDecimal(parameters.get("limit").toString()).intValueExact();
        var page = resource.page(limit);
        return Map.of("countOnPage", ((List<?>) page.get("items")).size(), "hasMore", page.get("hasMore"));
    }
}
