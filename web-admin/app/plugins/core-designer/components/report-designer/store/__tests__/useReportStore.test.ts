/**
 * Unit tests for useReportStore (lifecycle-only)
 *
 * After B1 Phase 2b the report document / selection / history live in the
 * unified-designer kernels (`ReportDocumentProvider`). This store keeps ONLY the
 * non-document lifecycle: pageId + the saving / loading / preview UI flags. The
 * document behavior is covered by `state/__tests__/ReportDocumentProvider.test.tsx`.
 */
import { describe, expect, it, beforeEach } from 'vitest';

import { useReportStore } from '../useReportStore';

describe('useReportStore (lifecycle-only)', () => {
  beforeEach(() => {
    useReportStore.getState().reset();
  });

  // ── Initial state ─────────────────────────────────────────────────────────

  it('starts with null pageId and clean UI flags', () => {
    const state = useReportStore.getState();
    expect(state.pageId).toBeNull();
    expect(state.isLoading).toBe(false);
    expect(state.isSaving).toBe(false);
    expect(state.previewMode).toBe(false);
  });

  // ── setPageId ───────────────────────────────────────────────────────────────

  describe('setPageId', () => {
    it('sets and clears the page id', () => {
      useReportStore.getState().setPageId('page-pid-1');
      expect(useReportStore.getState().pageId).toBe('page-pid-1');

      useReportStore.getState().setPageId(null);
      expect(useReportStore.getState().pageId).toBeNull();
    });
  });

  // ── setLoading ────────────────────────────────────────────────────────────

  describe('setLoading', () => {
    it('toggles the loading flag', () => {
      useReportStore.getState().setLoading(true);
      expect(useReportStore.getState().isLoading).toBe(true);
      useReportStore.getState().setLoading(false);
      expect(useReportStore.getState().isLoading).toBe(false);
    });
  });

  // ── setSaving ─────────────────────────────────────────────────────────────

  describe('setSaving', () => {
    it('toggles the saving flag', () => {
      useReportStore.getState().setSaving(true);
      expect(useReportStore.getState().isSaving).toBe(true);
      useReportStore.getState().setSaving(false);
      expect(useReportStore.getState().isSaving).toBe(false);
    });
  });

  // ── setPreviewMode ──────────────────────────────────────────────────────────

  describe('setPreviewMode', () => {
    it('toggles preview mode', () => {
      useReportStore.getState().setPreviewMode(true);
      expect(useReportStore.getState().previewMode).toBe(true);
      useReportStore.getState().setPreviewMode(false);
      expect(useReportStore.getState().previewMode).toBe(false);
    });
  });

  // ── reset ─────────────────────────────────────────────────────────────────

  describe('reset', () => {
    it('restores initial lifecycle state', () => {
      useReportStore.getState().setPageId('page-pid-1');
      useReportStore.getState().setSaving(true);
      useReportStore.getState().setLoading(true);
      useReportStore.getState().setPreviewMode(true);

      useReportStore.getState().reset();
      const state = useReportStore.getState();

      expect(state.pageId).toBeNull();
      expect(state.isSaving).toBe(false);
      expect(state.isLoading).toBe(false);
      expect(state.previewMode).toBe(false);
    });
  });
});
