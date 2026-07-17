package com.auraboot.framework.view.service;

import com.auraboot.framework.view.dto.ChipPinDTO;

import java.util.List;

/**
 * Pins of a SavedView to the current user's (or their team's) quick-filter chip
 * row. M2 implements personal pins; M3 adds the team-scoped branch.
 */
public interface SavedViewChipPinService {

    /**
     * Pin a view to the current user's personal quick-filter chip row. Idempotent:
     * pinning an already-pinned view updates its order rather than duplicating.
     */
    void pinPersonal(String viewPid, String modelCode, String pageKey, Integer order);

    /** Remove the current user's personal pin of a view (no-op if absent). */
    void unpinPersonal(String viewPid);

    /**
     * Pin a view to a team's quick-filter chip row so every team member sees it.
     * Requires the caller to hold team-manage and to belong to {@code teamId}.
     * Idempotent: re-pinning updates the order rather than duplicating.
     */
    void pinTeam(String viewPid, String teamId, String modelCode, String pageKey, Integer order);

    /**
     * Remove a team's pin of a view (no-op if absent). Same team-manage +
     * membership requirement as {@link #pinTeam}.
     */
    void unpinTeam(String viewPid, String teamId);

    /**
     * The effective pins for the current user on a model/page: their own personal
     * pins union the team pins of every team they belong to. Seeing a team pin is
     * read-level (membership); only authoring it needs team-manage. Returns
     * {@code {viewPid, order}} rows, de-duplicated by {@code viewPid}.
     */
    List<ChipPinDTO> listEffectivePins(String modelCode, String pageKey);
}
