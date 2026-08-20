import React, { useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  applyAuthoringAiPatchProposal,
  createAuthoringAiPatchProposal,
  rejectAuthoringAiPatchProposal,
} from '~/framework/meta/authoring/authoringService';
import type {
  AuthoringAiPatchProposal,
  AuthoringSession,
  CapabilityRegistry,
  PatchOperation,
} from '~/framework/meta/authoring/types';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { PageSchemaV3 } from '../types';
import {
  buildGovernedAiPatchPrompt,
  parseGovernedAiPatchResponse,
} from './governedAiPatch';

export interface GovernedAiPatchProposalDialogProps {
  open: boolean;
  onClose: () => void;
  sessionPid: string;
  revision: number;
  document: PageSchemaV3;
  capabilities: CapabilityRegistry;
  onApplied: (session: AuthoringSession) => void;
}

export function GovernedAiPatchProposalDialog({
  open,
  onClose,
  sessionPid,
  revision,
  document,
  capabilities,
  onApplied,
}: GovernedAiPatchProposalDialogProps) {
  const { locale } = useI18n();
  const copy = DESIGNER_I18N.unified.aiProposal;
  const [description, setDescription] = useState('');
  const [proposal, setProposal] = useState<AuthoringAiPatchProposal | null>(null);
  const [pending, setPending] = useState<'generate' | 'apply' | 'discard' | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const text = (value: Parameters<typeof resolveDesignerText>[0]) =>
    resolveDesignerText(value, locale);
  const resetAndClose = () => {
    setDescription('');
    setProposal(null);
    setPending(null);
    setError(null);
    onClose();
  };

  const handleGenerate = async () => {
    if (!description.trim() || pending) return;
    setPending('generate');
    setError(null);
    try {
      const systemPrompt = buildGovernedAiPatchPrompt({ document, capabilities });
      const response = await fetch('/api/agent/nl-modeling/generate-page', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ systemPrompt, message: description.trim() }),
      });
      const result = (await response.json().catch(() => ({}))) as {
        content?: string;
        error?: string;
      };
      if (!response.ok || !result.content) {
        throw new Error(result.error || `AI request failed (${response.status})`);
      }
      const items = parseGovernedAiPatchResponse(result.content, { document, capabilities });
      const serverProposal = await createAuthoringAiPatchProposal(sessionPid, revision, items);
      setProposal(serverProposal);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setPending(null);
    }
  };

  const handleApply = async () => {
    if (!proposal || pending) return;
    setPending('apply');
    setError(null);
    try {
      const result = await applyAuthoringAiPatchProposal(
        sessionPid,
        proposal.proposalPid,
        revision,
      );
      onApplied(result.session);
      resetAndClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setPending(null);
    }
  };

  const handleClose = async () => {
    if (pending) return;
    if (!proposal) {
      resetAndClose();
      return;
    }
    setPending('discard');
    setError(null);
    try {
      await rejectAuthoringAiPatchProposal(
        sessionPid,
        proposal.proposalPid,
        text(copy.rejectReason),
      );
      resetAndClose();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
      setPending(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="governed-ai-proposal-title"
        className="flex max-h-[min(760px,calc(100vh-2rem))] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-2xl"
        data-testid="governed-ai-proposal-dialog"
      >
        <header className="flex items-start justify-between gap-4 border-b border-slate-200 px-6 py-5">
          <div>
            <h2 id="governed-ai-proposal-title" className="text-lg font-semibold text-slate-950">
              ✨ {text(copy.title)}
            </h2>
            <p className="mt-1 max-w-2xl text-sm leading-5 text-slate-600">{text(copy.boundary)}</p>
          </div>
          <button
            type="button"
            aria-label={text(copy.cancel)}
            disabled={Boolean(pending)}
            onClick={() => void handleClose()}
            className="rounded-md px-2 py-1 text-xl text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-40"
          >
            ×
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
          {!proposal ? (
            <div className="space-y-4">
              <label className="block text-sm font-medium text-slate-800">
                {text(copy.description)}
                <textarea
                  autoFocus
                  rows={5}
                  value={description}
                  disabled={Boolean(pending)}
                  onChange={(event) => setDescription(event.target.value)}
                  placeholder={text(copy.placeholder)}
                  data-testid="governed-ai-description"
                  className="mt-2 w-full resize-none rounded-lg border border-slate-300 px-3 py-2 text-sm leading-6 outline-none focus:border-purple-500 focus:ring-2 focus:ring-purple-100"
                />
              </label>
              <div className="grid gap-3 sm:grid-cols-3">
                <BoundaryCard label={text(copy.typedOnly)} tone="purple" />
                <BoundaryCard label={text(copy.humanRequired)} tone="amber" />
                <BoundaryCard label={text(copy.noDraftMutation)} tone="slate" />
              </div>
              {pending === 'generate' ? (
                <div className="rounded-lg border border-purple-200 bg-purple-50 px-4 py-3 text-sm text-purple-800">
                  {text(copy.generating)}
                </div>
              ) : null}
            </div>
          ) : (
            <div className="space-y-4" data-testid="governed-ai-proposal-review">
              <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3">
                <div className="font-semibold text-emerald-950">{text(copy.reviewTitle)}</div>
                <p className="mt-1 text-sm leading-5 text-emerald-800">{text(copy.reviewHint)}</p>
              </div>
              <div className="grid gap-3 sm:grid-cols-3">
                <PolicyCard label={text(copy.risk)} value={riskLabel(proposal.aggregateRisk, copy, text)} />
                <PolicyCard
                  label={text(copy.route)}
                  value={routeLabel(proposal.aggregateRoute, copy, text)}
                />
                <PolicyCard
                  label={text(copy.publish)}
                  value={publishLabel(proposal.publishPolicy, copy, text)}
                />
              </div>
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold text-slate-900">
                  {text(copy.itemCount).replace('{count}', String(proposal.items.length))}
                </h3>
                <span className="rounded-full bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
                  {text(copy.noDraftMutation)}
                </span>
              </div>
              <ol className="space-y-2">
                {proposal.items.map((item) => (
                  <li
                    key={`${item.blockId}:${item.propertyPath}`}
                    className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3"
                    data-testid="governed-ai-proposal-item"
                  >
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="grid h-6 w-6 place-items-center rounded-full bg-white font-semibold text-slate-600 shadow-sm">
                        {item.ordinal}
                      </span>
                      <span className="font-medium text-slate-900">{item.blockId}</span>
                      <code className="rounded bg-white px-2 py-0.5 text-xs text-slate-600">
                        {item.propertyPath}
                      </code>
                      <span className="rounded-full bg-purple-100 px-2 py-0.5 text-xs font-medium text-purple-800">
                        {operationLabel(item.operation, copy, text)}
                      </span>
                    </div>
                    <div className="mt-3 grid gap-2 pl-8 text-xs sm:grid-cols-2">
                      <DiffValue
                        label={text(copy.beforeValue)}
                        value={item.previousValue}
                        fallback={text(copy.notSetValue)}
                      />
                      <DiffValue
                        label={text(copy.afterValue)}
                        value={item.value}
                        fallback={
                          item.operation === 'REMOVE'
                            ? text(copy.removedValue)
                            : text(copy.notSetValue)
                        }
                      />
                    </div>
                  </li>
                ))}
              </ol>
            </div>
          )}

          {error ? (
            <div
              role="alert"
              className="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
              data-testid="governed-ai-proposal-error"
            >
              {text(copy.failed).replace('{error}', error)}
            </div>
          ) : null}
        </div>

        <footer className="flex flex-wrap justify-end gap-3 border-t border-slate-200 bg-slate-50 px-6 py-4">
          <button
            type="button"
            disabled={Boolean(pending)}
            onClick={() => void handleClose()}
            className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-50"
            data-testid="governed-ai-proposal-discard"
          >
            {pending === 'discard' ? text(copy.discarding) : proposal ? text(copy.discard) : text(copy.cancel)}
          </button>
          {proposal ? (
            <button
              type="button"
              disabled={Boolean(pending)}
              onClick={() => void handleApply()}
              className="rounded-md bg-purple-700 px-4 py-2 text-sm font-semibold text-white hover:bg-purple-800 disabled:opacity-50"
              data-testid="governed-ai-proposal-apply"
            >
              {pending === 'apply' ? text(copy.applying) : text(copy.apply)}
            </button>
          ) : (
            <button
              type="button"
              disabled={Boolean(pending) || !description.trim()}
              onClick={() => void handleGenerate()}
              className="rounded-md bg-purple-700 px-4 py-2 text-sm font-semibold text-white hover:bg-purple-800 disabled:opacity-50"
              data-testid="governed-ai-proposal-generate"
            >
              {text(copy.generate)}
            </button>
          )}
        </footer>
      </section>
    </div>
  );
}

function BoundaryCard({ label, tone }: { label: string; tone: 'purple' | 'amber' | 'slate' }) {
  const styles = {
    purple: 'border-purple-200 bg-purple-50 text-purple-800',
    amber: 'border-amber-200 bg-amber-50 text-amber-800',
    slate: 'border-slate-200 bg-slate-50 text-slate-700',
  };
  return <div className={`rounded-lg border px-3 py-2 text-xs font-medium ${styles[tone]}`}>{label}</div>;
}

function PolicyCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white px-3 py-2">
      <div className="text-[11px] font-medium uppercase tracking-wide text-slate-400">{label}</div>
      <div className="mt-1 text-sm font-semibold text-slate-800">{value}</div>
    </div>
  );
}

type Copy = typeof DESIGNER_I18N.unified.aiProposal;
type Resolve = (value: Parameters<typeof resolveDesignerText>[0]) => string;

function operationLabel(operation: PatchOperation, copy: Copy, text: Resolve): string {
  if (operation === 'ADD') return text(copy.operationAdd);
  if (operation === 'REMOVE') return text(copy.operationRemove);
  return text(copy.operationReplace);
}

function DiffValue({ label, value, fallback }: { label: string; value: unknown; fallback: string }) {
  const hasValue = value !== undefined && value !== null;
  const rendered = hasValue
    ? ['string', 'number', 'boolean'].includes(typeof value)
      ? String(value)
      : JSON.stringify(value)
    : fallback;
  return (
    <div className="rounded-md border border-slate-200 bg-white px-3 py-2">
      <div className="font-medium text-slate-400">{label}</div>
      <div className="mt-1 break-all font-mono text-slate-700">{rendered}</div>
    </div>
  );
}

function riskLabel(risk: string, copy: Copy, text: Resolve): string {
  if (risk === 'L0') return text(copy.riskL0);
  if (risk === 'L1') return text(copy.riskL1);
  if (risk === 'L2') return text(copy.riskL2);
  return text(copy.riskL3);
}

function routeLabel(route: string, copy: Copy, text: Resolve): string {
  if (route === 'PERSONALIZE') return text(copy.routePersonalize);
  if (route === 'INLINE') return text(copy.routeInline);
  if (route === 'GUIDED_INLINE') return text(copy.routeGuided);
  return text(copy.routeStudio);
}

function publishLabel(policy: string, copy: Copy, text: Resolve): string {
  if (policy === 'DIRECT_ALLOWED') return text(copy.publishDirect);
  if (policy === 'DEFAULT_REVIEW') return text(copy.publishDefaultReview);
  if (policy === 'REQUIRED_REVIEW') return text(copy.publishRequiredReview);
  return text(copy.publishStudioApproval);
}
