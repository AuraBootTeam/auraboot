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
     * The effective pins for the current user on a model/page: their personal
     * pins now, plus team pins once M3 lands. Returns {@code {viewPid, order}} rows.
     */
    List<ChipPinDTO> listEffectivePins(String modelCode, String pageKey);
}
