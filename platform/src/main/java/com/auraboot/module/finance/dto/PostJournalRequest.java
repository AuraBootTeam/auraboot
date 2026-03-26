package com.auraboot.module.finance.dto;

import lombok.Data;

import java.util.List;

/**
 * Request to post a complete double-entry journal.
 * The sum of all debit amounts must equal the sum of all credit amounts.
 */
@Data
public class PostJournalRequest {

    /**
     * Descriptive label for the journal batch (e.g. "April rent expense").
     */
    private String description;

    /**
     * Individual debit/credit lines. At least two required; must balance.
     */
    private List<GlEntryRequest> entries;
}
