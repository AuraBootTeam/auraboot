/**
 * BpmPanelBlock - detail-page block that surfaces BPM (workflow) context for
 * the current record.
 *
 * This is the skeleton delivered as part of Task 10 of the OSS BPM closure
 * spec. It:
 *   1. Resolves the `businessKey` from the record based on block config.
 *   2. Fetches the process-instance status via `getInstanceForRecord`.
 *   3. Renders a container with up to 4 placeholder sections
 *      (status / diagram / operations / history). The actual section bodies
 *      are wired in later tasks (11-14).
 *
 * Loading / error / empty states are rendered explicitly; section placeholders
 * only appear when an instance is present.
 *
 * @since BPM closure spec 1 (Task 10)
 */

import { useCallback, useEffect, useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import { useI18n } from '~/contexts/I18nContext';
import { BpmStatusSection } from '~/plugins/core-bpm/components/panel/BpmStatusSection';
import { BpmDiagramSection } from '~/plugins/core-bpm/components/panel/BpmDiagramSection';
import { BpmOperationsSection } from '~/plugins/core-bpm/components/panel/BpmOperationsSection';
import { BpmHistorySection } from '~/plugins/core-bpm/components/panel/BpmHistorySection';
import { BpmRuleTraceSection } from '~/plugins/core-bpm/components/panel/BpmRuleTraceSection';
import {
  getInstanceForRecord,
  type BpmInstanceForRecord,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';

/**
 * Canonical section identifiers supported by the bpm-panel block. Order in
 * this array is the default render order; callers may override via
 * `block.bpmPanel.sections`.
 */
export const BPM_PANEL_SECTIONS = ['status', 'diagram', 'operations', 'history'] as const;
export type BpmPanelSection = (typeof BPM_PANEL_SECTIONS)[number];

/**
 * Shape of the optional `bpmPanel` configuration on a BlockConfig. Mirrors
 * the plan: `sections` narrows which section placeholders are rendered;
 * `businessKeyField` selects which record field to read as the business key;
 * `processKey` optionally scopes the instance lookup to a single process.
 */
export interface BpmPanelConfig {
  sections?: BpmPanelSection[];
  businessKeyField?: string;
  processKey?: string;
}

interface BpmPanelBlockProps {
  block: BlockConfig & { bpmPanel?: BpmPanelConfig };
  record: Record<string, unknown> | null | undefined;
  recordPid: string;
}

/** Render a single placeholder section with a stable `data-testid`. */
function SectionPlaceholder({ section }: { section: BpmPanelSection }) {
  return (
    <div
      data-testid={`bpm-section-${section}`}
      className="border-border-strong bg-panel text-text-3 rounded border border-dashed p-4 text-xs"
    >
      {section} section placeholder
    </div>
  );
}

export function BpmPanelBlock({ block, record, recordPid }: BpmPanelBlockProps) {
  const { t } = useI18n();
  const config = block.bpmPanel ?? {};
  const sections: BpmPanelSection[] =
    config.sections && config.sections.length > 0
      ? config.sections.filter((s): s is BpmPanelSection =>
          (BPM_PANEL_SECTIONS as readonly string[]).includes(s),
        )
      : [...BPM_PANEL_SECTIONS];

  // Resolve businessKey: prefer explicit field on record, fall back to
  // recordPid. We do NOT apply any multi-path fallback chain - a missing
  // configured field yields `undefined`, which short-circuits to recordPid.
  const businessKey =
    config.businessKeyField && record
      ? (record[config.businessKeyField] as string | number | undefined)
      : recordPid;

  const [instance, setInstance] = useState<BpmInstanceForRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  // Monotonic counter bumped by `handleReload`. Operations section calls
  // `onActionComplete` after a successful approve/reject/withdraw/cc so we
  // refetch the instance state and surface the updated status/current-nodes.
  const [reloadTick, setReloadTick] = useState(0);
  const handleReload = useCallback(() => {
    setReloadTick((t) => t + 1);
  }, []);

  useEffect(() => {
    if (businessKey === undefined || businessKey === null || businessKey === '') {
      setInstance(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    getInstanceForRecord(String(businessKey), config.processKey)
      .then((result) => {
        if (cancelled) return;
        setInstance(result);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err : new Error(String(err)));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [businessKey, config.processKey, reloadTick]);

  if (loading) {
    return (
      <div
        data-testid="bpm-panel"
        data-state="loading"
        className="border-border bg-subtle text-text-2 rounded border p-6 text-sm"
      >
        Loading workflow state...
      </div>
    );
  }

  if (error) {
    return (
      <div
        data-testid="bpm-panel"
        data-state="error"
        className="bg-status-red-bg rounded border border-status-red p-6 text-sm text-status-red"
      >
        Failed to load workflow state: {error.message}
      </div>
    );
  }

  if (!instance) {
    return (
      <div
        data-testid="bpm-panel"
        data-state="empty"
        className="border-border bg-panel text-text-2 rounded border p-6 text-sm"
      >
        No workflow instance for this record.
      </div>
    );
  }

  return (
    <div
      data-testid="bpm-panel"
      data-state="ready"
      data-process-instance-id={instance.instanceId}
      className="space-y-3"
    >
      {sections.map((section) => {
        if (section === 'status') {
          return (
            <div key={section} data-testid="bpm-section-status">
              <BpmStatusSection instance={instance} t={t} />
            </div>
          );
        }
        if (section === 'diagram') {
          return (
            <div key={section} data-testid="bpm-section-diagram">
              <BpmDiagramSection instance={instance} t={t} />
            </div>
          );
        }
        if (section === 'operations') {
          return (
            <div key={section} data-testid="bpm-section-operations">
              <BpmOperationsSection instance={instance} onActionComplete={handleReload} t={t} />
            </div>
          );
        }
        if (section === 'history') {
          return (
            <div key={section} data-testid="bpm-section-history">
              <div className="space-y-3">
                <BpmRuleTraceSection processInstanceId={instance.instanceId} t={t} />
                <BpmHistorySection instance={instance} t={t} />
              </div>
            </div>
          );
        }
        return <SectionPlaceholder key={section} section={section} />;
      })}
    </div>
  );
}
