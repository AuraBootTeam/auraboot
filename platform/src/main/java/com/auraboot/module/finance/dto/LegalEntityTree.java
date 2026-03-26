package com.auraboot.module.finance.dto;

import com.auraboot.module.finance.entity.LegalEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Tree representation of legal entities for a tenant.
 * Each node wraps a {@link LegalEntity} and carries its direct children.
 */
@Data
public class LegalEntityTree {

    private LegalEntity entity;
    private List<LegalEntityTree> children = new ArrayList<>();

    public LegalEntityTree(LegalEntity entity) {
        this.entity = entity;
    }

    /** Recursively add a child subtree. */
    public void addChild(LegalEntityTree child) {
        this.children.add(child);
    }
}
