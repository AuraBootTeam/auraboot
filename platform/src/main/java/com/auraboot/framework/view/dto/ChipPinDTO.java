package com.auraboot.framework.view.dto;

/**
 * An effective quick-filter chip pin for the current user: which view is pinned
 * and at what display order. Serialized as {@code {viewPid, order}} — the shape
 * the frontend chip assembler consumes.
 */
public record ChipPinDTO(String viewPid, int order) {
}
