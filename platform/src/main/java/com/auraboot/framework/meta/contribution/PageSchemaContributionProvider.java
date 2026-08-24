package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.meta.dto.PageSchemaDTO;

import java.util.List;

/** Replaceable source of active runtime page contributions. */
@FunctionalInterface
public interface PageSchemaContributionProvider {

    List<PageSchemaContribution> findActiveContributions(PageSchemaDTO pageSchema);

    static PageSchemaContributionProvider none() {
        return ignored -> List.of();
    }
}
