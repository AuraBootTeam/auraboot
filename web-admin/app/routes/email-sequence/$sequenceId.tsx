/**
 * EmailSequenceEditorPage — edit sequence metadata, steps, and enrollments.
 *
 * Features:
 *  - Editable name and description in header
 *  - Status toggle buttons (Activate / Pause / Archive)
 *  - Ordered list of steps with inline edit
 *  - Add Step form at bottom
 *  - Enrollment list with pause/resume actions
 *  - Enroll Contacts form
 */

import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  PlusIcon,
  PencilIcon,
  TrashIcon,
  CheckIcon,
  XMarkIcon,
  PlayIcon,
  PauseIcon,
  ArchiveBoxIcon,
} from '@heroicons/react/24/outline';
import {
  getSequence,
  updateSequence,
  updateSequenceStatus,
  listSteps,
  addStep,
  updateStep,
  deleteStep,
  listEnrollments,
  enrollContacts,
  pauseEnrollment,
  resumeEnrollment,
  listAccounts,
  type EmailSequence,
  type EmailSequenceStep,
  type EmailSequenceEnrollment,
  type EmailAccount,
} from '~/services/emailService';
import { useToastContext } from '~/contexts/ToastContext';

function statusBadge(status: string) {
  const styles: Record<string, string> = {
    draft: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
    active: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    paused: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
    archived: 'bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-500',
  };
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-xs font-medium capitalize ${styles[status] || styles.draft}`}
    >
      {status}
    </span>
  );
}

function enrollmentStatusBadge(status: string) {
  const styles: Record<string, string> = {
    active: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    paused: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
    completed: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
    unsubscribed: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
  };
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-xs font-medium capitalize ${styles[status] || styles.active}`}
    >
      {status}
    </span>
  );
}

interface StepRowProps {
  step: EmailSequenceStep;
  sequenceId: number;
  onUpdated: (step: EmailSequenceStep) => void;
  onDeleted: (stepId: number) => void;
}

function StepRow({ step, sequenceId, onUpdated, onDeleted }: StepRowProps) {
  const [editing, setEditing] = useState(false);
  const [delayDays, setDelayDays] = useState(String(step.delayDays));
  const [subject, setSubject] = useState(step.subjectTemplate);
  const [body, setBody] = useState(step.bodyTemplate);
  const [saving, setSaving] = useState(false);
  const { showSuccessToast, showErrorToast } = useToastContext();

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateStep(sequenceId, step.id, {
        delayDays: Number(delayDays),
        subjectTemplate: subject,
        bodyTemplate: body,
      });
      onUpdated({
        ...step,
        delayDays: Number(delayDays),
        subjectTemplate: subject,
        bodyTemplate: body,
      });
      setEditing(false);
      showSuccessToast('Step updated');
    } catch {
      showErrorToast('Failed to update step');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    try {
      await deleteStep(sequenceId, step.id);
      onDeleted(step.id);
      showSuccessToast('Step deleted');
    } catch {
      showErrorToast('Failed to delete step');
    }
  };

  return (
    <div
      className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
      data-testid={`step-row-${step.id}`}
    >
      {!editing ? (
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-sm font-medium text-gray-900 dark:text-white">
              Step {step.stepOrder} &bull; Day {step.delayDays}
            </p>
            <p className="mt-0.5 text-sm text-gray-600 dark:text-gray-400">
              {step.subjectTemplate}
            </p>
            {step.bodyTemplate && (
              <p className="mt-1 line-clamp-2 text-xs text-gray-400 dark:text-gray-500">
                {step.bodyTemplate}
              </p>
            )}
          </div>
          <div className="flex flex-shrink-0 gap-1">
            <button
              onClick={() => setEditing(true)}
              className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700"
              title="Edit step"
            >
              <PencilIcon className="h-4 w-4" />
            </button>
            <button
              onClick={handleDelete}
              className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
              title="Delete step"
            >
              <TrashIcon className="h-4 w-4" />
            </button>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex items-center gap-3">
            <label className="w-24 flex-shrink-0 text-xs font-medium text-gray-500">
              Delay (days)
            </label>
            <input
              type="number"
              min="0"
              value={delayDays}
              onChange={(e) => setDelayDays(e.target.value)}
              className="w-24 rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500">Subject Template</label>
            <input
              type="text"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              className="w-full rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-500">Body Template</label>
            <textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={4}
              className="w-full rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            />
          </div>
          <div className="flex gap-2">
            <button
              onClick={handleSave}
              disabled={saving}
              className="flex items-center gap-1 rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-60"
            >
              <CheckIcon className="h-3.5 w-3.5" /> Save
            </button>
            <button
              onClick={() => setEditing(false)}
              className="flex items-center gap-1 rounded-lg border border-gray-200 px-3 py-1.5 text-xs text-gray-600 dark:border-gray-600 dark:text-gray-300"
            >
              <XMarkIcon className="h-3.5 w-3.5" /> Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default function EmailSequenceEditorPage() {
  const { sequenceId } = useParams<{ sequenceId: string }>();
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const seqId = Number(sequenceId);

  const [sequence, setSequence] = useState<EmailSequence | null>(null);
  const [steps, setSteps] = useState<EmailSequenceStep[]>([]);
  const [enrollments, setEnrollments] = useState<EmailSequenceEnrollment[]>([]);
  const [accounts, setAccounts] = useState<EmailAccount[]>([]);
  const [loading, setLoading] = useState(true);

  // Header edit state
  const [editingName, setEditingName] = useState(false);
  const [nameValue, setNameValue] = useState('');
  const [editingDesc, setEditingDesc] = useState(false);
  const [descValue, setDescValue] = useState('');

  // Add step state
  const [showAddStep, setShowAddStep] = useState(false);
  const [newDelay, setNewDelay] = useState('1');
  const [newSubject, setNewSubject] = useState('');
  const [newBody, setNewBody] = useState('');
  const [addingStep, setAddingStep] = useState(false);

  // Enroll state
  const [showEnroll, setShowEnroll] = useState(false);
  const [enrollEmail, setEnrollEmail] = useState('');
  const [enrollAccountId, setEnrollAccountId] = useState<number | null>(null);
  const [enrolling, setEnrolling] = useState(false);

  const load = useCallback(async () => {
    if (!seqId) return;
    setLoading(true);
    try {
      const [seq, stepList, enrollList, accts] = await Promise.all([
        getSequence(seqId),
        listSteps(seqId),
        listEnrollments(seqId),
        listAccounts(),
      ]);
      if (seq) {
        setSequence(seq);
        setNameValue(seq.name);
        setDescValue(seq.description || '');
      }
      setSteps(stepList);
      setEnrollments(enrollList);
      setAccounts(accts.filter((a) => a.status === 'active'));
      if (accts.length > 0) setEnrollAccountId(accts[0].id);
    } catch {
      showErrorToast('Failed to load sequence');
    } finally {
      setLoading(false);
    }
  }, [seqId]);

  useEffect(() => {
    load();
  }, [load]);

  const handleSaveName = async () => {
    if (!nameValue.trim() || !sequence) return;
    try {
      await updateSequence(seqId, { name: nameValue.trim() });
      setSequence((s) => (s ? { ...s, name: nameValue.trim() } : s));
      setEditingName(false);
      showSuccessToast('Name updated');
    } catch {
      showErrorToast('Failed to update name');
    }
  };

  const handleSaveDesc = async () => {
    if (!sequence) return;
    try {
      await updateSequence(seqId, { description: descValue.trim() || undefined });
      setSequence((s) => (s ? { ...s, description: descValue.trim() } : s));
      setEditingDesc(false);
      showSuccessToast('Description updated');
    } catch {
      showErrorToast('Failed to update description');
    }
  };

  const handleStatusChange = async (status: 'active' | 'paused' | 'archived') => {
    try {
      await updateSequenceStatus(seqId, status);
      setSequence((s) => (s ? { ...s, status } : s));
      showSuccessToast(`Sequence ${status}`);
    } catch {
      showErrorToast('Failed to update status');
    }
  };

  const handleAddStep = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newSubject.trim()) return;
    setAddingStep(true);
    try {
      const nextOrder = steps.length + 1;
      const step = await addStep(seqId, {
        stepOrder: nextOrder,
        delayDays: Number(newDelay),
        subjectTemplate: newSubject.trim(),
        bodyTemplate: newBody.trim(),
      });
      if (step) {
        setSteps((prev) => [...prev, step]);
        setNewDelay('1');
        setNewSubject('');
        setNewBody('');
        setShowAddStep(false);
        showSuccessToast('Step added');
      }
    } catch {
      showErrorToast('Failed to add step');
    } finally {
      setAddingStep(false);
    }
  };

  const handleEnroll = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!enrollEmail.trim() || !enrollAccountId) return;
    setEnrolling(true);
    try {
      await enrollContacts(seqId, [
        { accountId: enrollAccountId, contactEmail: enrollEmail.trim() },
      ]);
      showSuccessToast('Contact enrolled');
      setEnrollEmail('');
      setShowEnroll(false);
      // Refresh enrollments
      listEnrollments(seqId).then(setEnrollments);
    } catch {
      showErrorToast('Failed to enroll contact');
    } finally {
      setEnrolling(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-32">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (!sequence) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-16 text-center text-gray-400">
        Sequence not found
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl px-4 py-6" data-testid="email-sequence-editor-page">
      {/* Back */}
      <button
        onClick={() => navigate('/email-sequence')}
        className="mb-4 flex items-center gap-1.5 text-sm text-gray-500 transition-colors hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
      >
        <ArrowLeftIcon className="h-4 w-4" />
        Back to sequences
      </button>

      {/* Header */}
      <div className="mb-6 rounded-xl border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            {/* Name */}
            {editingName ? (
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={nameValue}
                  onChange={(e) => setNameValue(e.target.value)}
                  autoFocus
                  data-testid="sequence-editor-name"
                  className="flex-1 rounded-lg border border-blue-400 px-3 py-1.5 text-lg font-semibold text-gray-900 dark:bg-gray-800 dark:text-white"
                />
                <button
                  onClick={handleSaveName}
                  className="rounded-lg bg-blue-600 p-1.5 text-white"
                >
                  <CheckIcon className="h-4 w-4" />
                </button>
                <button
                  onClick={() => setEditingName(false)}
                  className="rounded-lg border border-gray-200 p-1.5 text-gray-500"
                >
                  <XMarkIcon className="h-4 w-4" />
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <h1 className="text-xl font-semibold text-gray-900 dark:text-white">
                  {sequence.name}
                </h1>
                {statusBadge(sequence.status)}
                <button
                  onClick={() => setEditingName(true)}
                  className="rounded p-1 text-gray-400 hover:text-gray-600"
                >
                  <PencilIcon className="h-3.5 w-3.5" />
                </button>
              </div>
            )}

            {/* Description */}
            {editingDesc ? (
              <div className="mt-2 flex items-center gap-2">
                <input
                  type="text"
                  value={descValue}
                  onChange={(e) => setDescValue(e.target.value)}
                  placeholder="Add description…"
                  className="flex-1 rounded-lg border border-blue-400 px-3 py-1 text-sm text-gray-700 dark:bg-gray-800 dark:text-white"
                />
                <button
                  onClick={handleSaveDesc}
                  className="rounded-lg bg-blue-600 p-1.5 text-white"
                >
                  <CheckIcon className="h-3.5 w-3.5" />
                </button>
                <button
                  onClick={() => setEditingDesc(false)}
                  className="rounded-lg border border-gray-200 p-1.5 text-gray-500"
                >
                  <XMarkIcon className="h-3.5 w-3.5" />
                </button>
              </div>
            ) : (
              <button
                onClick={() => setEditingDesc(true)}
                className="mt-1 flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 dark:text-gray-400"
              >
                {sequence.description || (
                  <span className="text-gray-400 dark:text-gray-500">Add description…</span>
                )}
                <PencilIcon className="h-3 w-3" />
              </button>
            )}
          </div>

          {/* Status actions */}
          <div className="flex flex-shrink-0 gap-2">
            {sequence.status !== 'active' && sequence.status !== 'archived' && (
              <button
                onClick={() => handleStatusChange('active')}
                data-testid="activate-sequence-btn"
                className="flex items-center gap-1.5 rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700"
              >
                <PlayIcon className="h-4 w-4" /> Activate
              </button>
            )}
            {sequence.status === 'active' && (
              <button
                onClick={() => handleStatusChange('paused')}
                data-testid="pause-sequence-btn"
                className="flex items-center gap-1.5 rounded-lg bg-yellow-500 px-3 py-1.5 text-sm font-medium text-white hover:bg-yellow-600"
              >
                <PauseIcon className="h-4 w-4" /> Pause
              </button>
            )}
            {sequence.status !== 'archived' && (
              <button
                onClick={() => handleStatusChange('archived')}
                data-testid="archive-sequence-btn"
                className="flex items-center gap-1.5 rounded-lg border border-gray-200 px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300"
              >
                <ArchiveBoxIcon className="h-4 w-4" /> Archive
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Steps section */}
      <section className="mb-8">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900 dark:text-white">
            Steps ({steps.length})
          </h2>
          <button
            onClick={() => setShowAddStep((v) => !v)}
            data-testid="add-step-btn"
            className="flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            <PlusIcon className="h-4 w-4" /> Add Step
          </button>
        </div>

        {steps.length === 0 ? (
          <div className="rounded-xl border border-dashed border-gray-300 py-8 text-center text-sm text-gray-400 dark:border-gray-600">
            No steps yet. Click &ldquo;Add Step&rdquo; to get started.
          </div>
        ) : (
          <div className="space-y-3">
            {steps.map((step) => (
              <StepRow
                key={step.id}
                step={step}
                sequenceId={seqId}
                onUpdated={(updated) =>
                  setSteps((prev) => prev.map((s) => (s.id === updated.id ? updated : s)))
                }
                onDeleted={(stepId) => setSteps((prev) => prev.filter((s) => s.id !== stepId))}
              />
            ))}
          </div>
        )}

        {/* Add step form */}
        {showAddStep && (
          <form
            onSubmit={handleAddStep}
            className="mt-4 space-y-3 rounded-xl border border-blue-200 bg-blue-50 p-5 dark:border-blue-800/50 dark:bg-blue-900/10"
            data-testid="add-step-form"
          >
            <h3 className="text-sm font-semibold text-gray-900 dark:text-white">New Step</h3>
            <div className="flex items-center gap-3">
              <label className="w-24 text-xs font-medium text-gray-500">Delay (days)</label>
              <input
                type="number"
                min="0"
                value={newDelay}
                onChange={(e) => setNewDelay(e.target.value)}
                className="w-24 rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-500">
                Subject Template <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={newSubject}
                onChange={(e) => setNewSubject(e.target.value)}
                placeholder="e.g. Follow up regarding {{company}}"
                required
                className="w-full rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-500">Body Template</label>
              <textarea
                value={newBody}
                onChange={(e) => setNewBody(e.target.value)}
                rows={4}
                placeholder="Hi {{first_name}},&#10;&#10;I wanted to follow up…"
                className="w-full rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
            </div>
            <div className="flex gap-2">
              <button
                type="submit"
                disabled={addingStep || !newSubject.trim()}
                className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
              >
                {addingStep ? (
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                ) : null}
                Add Step
              </button>
              <button
                type="button"
                onClick={() => setShowAddStep(false)}
                className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 dark:border-gray-600 dark:text-gray-300"
              >
                Cancel
              </button>
            </div>
          </form>
        )}
      </section>

      {/* Enrollments section */}
      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900 dark:text-white">
            Enrollments ({enrollments.length})
          </h2>
          <button
            onClick={() => setShowEnroll((v) => !v)}
            data-testid="enroll-contacts-btn"
            className="flex items-center gap-1.5 rounded-lg border border-gray-200 px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300"
          >
            <PlusIcon className="h-4 w-4" /> Enroll Contact
          </button>
        </div>

        {/* Enroll form */}
        {showEnroll && (
          <form
            onSubmit={handleEnroll}
            className="mb-4 space-y-3 rounded-xl border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800/50"
            data-testid="enroll-form"
          >
            <h3 className="text-sm font-semibold text-gray-900 dark:text-white">Enroll Contact</h3>
            <div className="flex items-center gap-3">
              <label className="w-28 text-xs font-medium text-gray-500">Account</label>
              <select
                value={enrollAccountId ?? ''}
                onChange={(e) => setEnrollAccountId(Number(e.target.value))}
                className="flex-1 rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              >
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.emailAddress}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex items-center gap-3">
              <label className="w-28 text-xs font-medium text-gray-500">Contact Email</label>
              <input
                type="email"
                value={enrollEmail}
                onChange={(e) => setEnrollEmail(e.target.value)}
                placeholder="contact@example.com"
                required
                className="flex-1 rounded-lg border border-gray-200 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
            </div>
            <div className="flex gap-2">
              <button
                type="submit"
                disabled={enrolling || !enrollEmail.trim()}
                className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
              >
                {enrolling && (
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                )}
                Enroll
              </button>
              <button
                type="button"
                onClick={() => setShowEnroll(false)}
                className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 dark:border-gray-600 dark:text-gray-300"
              >
                Cancel
              </button>
            </div>
          </form>
        )}

        {enrollments.length === 0 ? (
          <div className="rounded-xl border border-dashed border-gray-300 py-8 text-center text-sm text-gray-400 dark:border-gray-600">
            No contacts enrolled yet.
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
            <table className="w-full text-sm" data-testid="enrollments-table">
              <thead>
                <tr className="border-b border-gray-200 dark:border-gray-700">
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wide text-gray-500 uppercase">
                    Email
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wide text-gray-500 uppercase">
                    Status
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wide text-gray-500 uppercase">
                    Step
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wide text-gray-500 uppercase">
                    Next Send
                  </th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                {enrollments.map((e) => (
                  <tr key={e.id} data-testid={`enrollment-row-${e.id}`}>
                    <td className="px-4 py-3 text-gray-700 dark:text-gray-300">{e.contactEmail}</td>
                    <td className="px-4 py-3">{enrollmentStatusBadge(e.status)}</td>
                    <td className="px-4 py-3 text-gray-500">{e.currentStep}</td>
                    <td className="px-4 py-3 text-gray-500">
                      {e.nextSendAt ? new Date(e.nextSendAt).toLocaleDateString() : '—'}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {e.status === 'active' ? (
                        <button
                          onClick={() =>
                            pauseEnrollment(sequence.id, e.id).then(() =>
                              setEnrollments((prev) =>
                                prev.map((en) =>
                                  en.id === e.id ? { ...en, status: 'paused' } : en,
                                ),
                              ),
                            )
                          }
                          className="text-xs text-yellow-600 hover:underline"
                        >
                          Pause
                        </button>
                      ) : e.status === 'paused' ? (
                        <button
                          onClick={() =>
                            resumeEnrollment(sequence.id, e.id).then(() =>
                              setEnrollments((prev) =>
                                prev.map((en) =>
                                  en.id === e.id ? { ...en, status: 'active' } : en,
                                ),
                              ),
                            )
                          }
                          className="text-xs text-green-600 hover:underline"
                        >
                          Resume
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
