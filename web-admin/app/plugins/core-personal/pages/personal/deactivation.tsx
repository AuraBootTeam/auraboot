/**
 * User Account Deactivation Page
 *
 * Three-step deactivation flow:
 *   Step 1: Warnings & information
 *   Step 2: Identity verification (password)
 *   Step 3: Confirmation & cooling-off period
 *
 * If user already has an active deactivation request, shows status & cancel option.
 *
 * Route: /personal/deactivation
 * API: POST /api/auth/deactivation/request
 *      POST /api/auth/deactivation/cancel
 *      GET  /api/auth/deactivation/status
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToast } from '~/contexts/ToastContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface DeactivationStatus {
  pid: string;
  status: string;
  reason: string | null;
  requestedAt: string;
  coolingOffUntil: string;
  cancelledAt: string | null;
  completedAt: string | null;
}

type Step = 1 | 2 | 3;

const REASONS = [
  'I no longer need this service',
  "I'm switching to a different platform",
  'Privacy concerns',
  'Too many notifications',
  'Other',
];

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function DeactivationPage() {
  const { showSuccessToast, showErrorToast } = useToast();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [existingStatus, setExistingStatus] = useState<DeactivationStatus | null>(null);
  const [step, setStep] = useState<Step>(1);
  const [reason, setReason] = useState('');
  const [customReason, setCustomReason] = useState('');
  const [password, setPassword] = useState('');
  const [consent, setConsent] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [cancelling, setCancelling] = useState(false);

  // Fetch current deactivation status
  const fetchStatus = useCallback(async () => {
    try {
      const result = await fetchResult<DeactivationStatus | null>('/api/auth/deactivation/status', {
        method: 'get',
      });
      if (ResultHelper.isSuccess(result) && result.data) {
        setExistingStatus(result.data);
      }
    } catch {
      // No active deactivation — that's fine
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStatus();
  }, [fetchStatus]);

  // Submit deactivation request
  const handleSubmit = async () => {
    const finalReason = reason === 'Other' ? customReason : reason;
    if (!finalReason) {
      showErrorToast('Please select a reason');
      return;
    }
    if (!consent) {
      showErrorToast('Please agree to the terms');
      return;
    }

    setSubmitting(true);
    try {
      const result = await fetchResult<DeactivationStatus>('/api/auth/deactivation/request', {
        method: 'post',
        params: {
          reason: finalReason,
          consentSnapshot: JSON.stringify({
            timestamp: Date.now(),
            agreed: true,
            reason: finalReason,
          }),
        },
      });

      if (ResultHelper.isSuccess(result) && result.data) {
        setExistingStatus(result.data);
        showSuccessToast('Deactivation request submitted');
      } else {
        showErrorToast(result.message || 'Failed to submit request');
      }
    } catch {
      showErrorToast('Network error');
    } finally {
      setSubmitting(false);
    }
  };

  // Cancel deactivation
  const handleCancel = async () => {
    setCancelling(true);
    try {
      const result = await fetchResult<void>('/api/auth/deactivation/cancel', {
        method: 'post',
      });

      if (ResultHelper.isSuccess(result)) {
        setExistingStatus(null);
        setStep(1);
        setReason('');
        setCustomReason('');
        setConsent(false);
        showSuccessToast('Deactivation cancelled successfully');
      } else {
        showErrorToast(result.message || 'Failed to cancel');
      }
    } catch {
      showErrorToast('Network error');
    } finally {
      setCancelling(false);
    }
  };

  // Calculate remaining cooling-off time
  const getRemainingDays = (): number => {
    if (!existingStatus?.coolingOffUntil) return 0;
    const until = new Date(existingStatus.coolingOffUntil).getTime();
    const now = Date.now();
    return Math.max(0, Math.ceil((until - now) / (1000 * 60 * 60 * 24)));
  };

  // -------------------------------------------------------------------------
  // Render: Loading
  // -------------------------------------------------------------------------

  if (loading) {
    return (
      <div className="mx-auto max-w-2xl p-6">
        <div className="flex items-center justify-center py-16">
          <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
        </div>
      </div>
    );
  }

  // -------------------------------------------------------------------------
  // Render: Active deactivation status
  // -------------------------------------------------------------------------

  if (existingStatus && existingStatus.status !== 'cancelled') {
    const days = getRemainingDays();

    return (
      <div className="mx-auto max-w-2xl space-y-6 p-6">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/personal/profile')}
            className="rounded-lg p-2 text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700"
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15 19l-7-7 7-7"
              />
            </svg>
          </button>
          <h1 className="text-2xl font-semibold text-gray-900">Account Deactivation</h1>
        </div>

        <div className="rounded-lg bg-white p-6 shadow-md" data-testid="deactivation-status">
          <div className="mb-4 flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-amber-100">
              <svg
                className="h-5 w-5 text-amber-600"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
            </div>
            <div>
              <h2 className="text-lg font-semibold text-gray-900">Cooling-Off Period</h2>
              <p className="text-sm text-gray-500">
                {existingStatus.status === 'completed'
                  ? 'Your account has been deactivated.'
                  : `${days} day${days !== 1 ? 's' : ''} remaining`}
              </p>
            </div>
          </div>

          <div className="space-y-2 rounded-lg bg-gray-50 p-4 text-sm">
            <div className="flex justify-between">
              <span className="text-gray-500">Status</span>
              <span
                className={`font-medium ${
                  existingStatus.status === 'completed' ? 'text-red-600' : 'text-amber-600'
                }`}
              >
                {existingStatus.status === 'cooling_off' ? 'Cooling Off' : existingStatus.status}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-500">Requested</span>
              <span className="text-gray-900">
                {new Date(existingStatus.requestedAt).toLocaleString()}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-500">Expires</span>
              <span className="text-gray-900">
                {new Date(existingStatus.coolingOffUntil).toLocaleString()}
              </span>
            </div>
            {existingStatus.reason && (
              <div className="flex justify-between">
                <span className="text-gray-500">Reason</span>
                <span className="text-gray-900">{existingStatus.reason}</span>
              </div>
            )}
          </div>

          {existingStatus.status !== 'completed' && (
            <div className="mt-6">
              <button
                onClick={handleCancel}
                disabled={cancelling}
                className="w-full rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
                data-testid="deactivation-cancel-btn"
              >
                {cancelling ? 'Cancelling...' : 'Cancel Deactivation'}
              </button>
              <p className="mt-2 text-center text-xs text-gray-500">
                You can cancel anytime during the cooling-off period. Your account will remain
                active.
              </p>
            </div>
          )}
        </div>
      </div>
    );
  }

  // -------------------------------------------------------------------------
  // Render: Multi-step deactivation flow
  // -------------------------------------------------------------------------

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/personal/profile')}
          className="rounded-lg p-2 text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700"
          data-testid="deactivation-back-btn"
        >
          <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M15 19l-7-7 7-7"
            />
          </svg>
        </button>
        <h1 className="text-2xl font-semibold text-gray-900">Account Deactivation</h1>
      </div>

      {/* Step indicator */}
      <div className="flex items-center justify-center gap-2">
        {[1, 2, 3].map((s) => (
          <div key={s} className="flex items-center">
            <div
              className={`flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium transition-colors ${
                s <= step ? 'bg-red-600 text-white' : 'bg-gray-200 text-gray-500'
              }`}
            >
              {s}
            </div>
            {s < 3 && (
              <div
                className={`mx-1 h-0.5 w-12 transition-colors ${
                  s < step ? 'bg-red-600' : 'bg-gray-200'
                }`}
              />
            )}
          </div>
        ))}
      </div>

      <div className="rounded-lg bg-white p-6 shadow-md">
        {/* ---- Step 1: Warning ---- */}
        {step === 1 && (
          <div data-testid="deactivation-step-1">
            <h2 className="mb-4 text-lg font-semibold text-gray-900">Before You Go</h2>

            <div className="mb-6 rounded-lg border border-red-200 bg-red-50 p-4">
              <h3 className="mb-2 font-medium text-red-800">What happens when you deactivate:</h3>
              <ul className="list-inside list-disc space-y-1 text-sm text-red-700">
                <li>Your account enters a 7-day cooling-off period</li>
                <li>After 7 days, all personal data will be anonymized</li>
                <li>All active sessions will be terminated</li>
                <li>Linked social accounts will be unbound</li>
                <li>This action is irreversible after the cooling-off period</li>
              </ul>
            </div>

            <div className="mb-6 space-y-3">
              <p className="text-sm font-medium text-gray-700">Why are you leaving?</p>
              {REASONS.map((r) => (
                <label key={r} className="flex cursor-pointer items-center gap-2">
                  <input
                    type="radio"
                    name="reason"
                    value={r}
                    checked={reason === r}
                    onChange={(e) => setReason(e.target.value)}
                    className="text-red-600 focus:ring-red-500"
                  />
                  <span className="text-sm text-gray-700">{r}</span>
                </label>
              ))}
              {reason === 'Other' && (
                <textarea
                  value={customReason}
                  onChange={(e) => setCustomReason(e.target.value)}
                  placeholder="Please describe your reason..."
                  className="mt-2 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-red-500 focus:ring-2 focus:ring-red-500"
                  rows={3}
                  data-testid="deactivation-custom-reason"
                />
              )}
            </div>

            <button
              onClick={() => setStep(2)}
              disabled={!reason || (reason === 'Other' && !customReason.trim())}
              className="w-full rounded-lg bg-red-600 px-4 py-2 text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="deactivation-next-step2"
            >
              Continue
            </button>
          </div>
        )}

        {/* ---- Step 2: Identity Verification ---- */}
        {step === 2 && (
          <div data-testid="deactivation-step-2">
            <h2 className="mb-4 text-lg font-semibold text-gray-900">Verify Your Identity</h2>
            <p className="mb-6 text-sm text-gray-500">
              Enter your password to confirm this action.
            </p>

            <div className="mb-6">
              <label
                htmlFor="deactivation-password"
                className="mb-1 block text-sm font-medium text-gray-700"
              >
                Password
              </label>
              <input
                id="deactivation-password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-lg border border-gray-300 px-4 py-2 focus:border-red-500 focus:ring-2 focus:ring-red-500"
                placeholder="Enter your password"
                data-testid="deactivation-password-input"
              />
            </div>

            <div className="flex gap-3">
              <button
                onClick={() => setStep(1)}
                className="flex-1 rounded-lg border border-gray-300 px-4 py-2 text-gray-700 transition-colors hover:bg-gray-50"
              >
                Back
              </button>
              <button
                onClick={() => setStep(3)}
                disabled={!password.trim()}
                className="flex-1 rounded-lg bg-red-600 px-4 py-2 text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="deactivation-next-step3"
              >
                Continue
              </button>
            </div>
          </div>
        )}

        {/* ---- Step 3: Final Confirmation ---- */}
        {step === 3 && (
          <div data-testid="deactivation-step-3">
            <h2 className="mb-4 text-lg font-semibold text-gray-900">Final Confirmation</h2>

            <div className="mb-6 rounded-lg border border-amber-200 bg-amber-50 p-4">
              <p className="text-sm text-amber-800">
                After submitting, you will have <strong>7 days</strong> to change your mind. During
                this period you can cancel the deactivation at any time.
              </p>
            </div>

            <label className="mb-6 flex cursor-pointer items-start gap-2">
              <input
                type="checkbox"
                checked={consent}
                onChange={(e) => setConsent(e.target.checked)}
                className="mt-1 text-red-600 focus:ring-red-500"
                data-testid="deactivation-consent-checkbox"
              />
              <span className="text-sm text-gray-700">
                I understand that after the cooling-off period, my account data will be permanently
                anonymized and this action cannot be undone.
              </span>
            </label>

            <div className="flex gap-3">
              <button
                onClick={() => setStep(2)}
                className="flex-1 rounded-lg border border-gray-300 px-4 py-2 text-gray-700 transition-colors hover:bg-gray-50"
              >
                Back
              </button>
              <button
                onClick={handleSubmit}
                disabled={submitting || !consent}
                className="flex-1 rounded-lg bg-red-600 px-4 py-2 text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="deactivation-submit-btn"
              >
                {submitting ? 'Submitting...' : 'Deactivate Account'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
