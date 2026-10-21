import React, { useCallback, useEffect } from 'react';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { type InviteCodeData, INVITE_LABELS } from './listPageStateUtils';

interface ListMemberInviteInput {
  locale: string;
  t: (key: string) => string;
  inviteContextRef: React.RefObject<{ allowed: boolean; open: boolean }>;
  inviteReadSeqRef: React.RefObject<number>;
  setInviteReadLoading: React.Dispatch<React.SetStateAction<boolean>>;
  setInviteReadError: React.Dispatch<React.SetStateAction<string | null>>;
  token: string | null | undefined;
  setInviteCodeData: React.Dispatch<React.SetStateAction<InviteCodeData | null>>;
  canInviteMembers: boolean;
  inviteDialogOpen: boolean;
  setInviteDialogOpen: React.Dispatch<React.SetStateAction<boolean>>;
  inviteLoading: boolean;
  inviteReadLoading: boolean;
  inviteReadError: string | null;
  setInviteLoading: React.Dispatch<React.SetStateAction<boolean>>;
  showErrorToast: (message: string, duration?: number) => void;
  showSuccessToast: (message: string, duration?: number) => void;
  inviteCodeData: InviteCodeData | null;
}

/** Read, generate and revoke tenant member invite codes. */
export function useListMemberInvite({
  locale,
  t,
  inviteContextRef,
  inviteReadSeqRef,
  setInviteReadLoading,
  setInviteReadError,
  token,
  setInviteCodeData,
  canInviteMembers,
  inviteDialogOpen,
  setInviteDialogOpen,
  inviteLoading,
  inviteReadLoading,
  inviteReadError,
  setInviteLoading,
  showErrorToast,
  showSuccessToast,
  inviteCodeData,
}: ListMemberInviteInput) {
  const inviteText = useCallback(
    (key: keyof typeof INVITE_LABELS) => getLocalizedText(INVITE_LABELS[key], locale, t),
    [locale, t],
  );

  const loadCurrentInviteCode = useCallback(
    async (
      failureKey: 'readFailed' | 'generatedReadFailed' | 'revokedReadFailed' = 'readFailed',
      expectedCode?: string,
    ): Promise<boolean> => {
      if (!inviteContextRef.current.allowed || !inviteContextRef.current.open) return false;
      const sequence = ++inviteReadSeqRef.current;
      setInviteReadLoading(true);
      setInviteReadError(null);
      try {
        const result = await fetchResult<InviteCodeData | null>('/api/tenant/invite-code/current', {
          method: 'get',
          token: token || undefined,
        });
        if (
          !ResultHelper.isSuccess(result) ||
          (result.data != null && typeof result.data.code !== 'string') ||
          (expectedCode && result.data?.code !== expectedCode)
        ) {
          throw new Error('Invalid current invite response');
        }
        if (sequence !== inviteReadSeqRef.current) return false;
        setInviteCodeData(result.data ?? null);
        return true;
      } catch {
        if (sequence !== inviteReadSeqRef.current) return false;
        setInviteCodeData(null);
        setInviteReadError(inviteText(failureKey));
        return false;
      } finally {
        if (sequence === inviteReadSeqRef.current) setInviteReadLoading(false);
      }
    },
    [canInviteMembers, inviteDialogOpen, inviteText, token],
  );

  useEffect(() => {
    if (!canInviteMembers || !inviteDialogOpen) {
      setInviteCodeData(null);
      setInviteReadError(null);
      setInviteReadLoading(false);
      if (!canInviteMembers) setInviteDialogOpen(false);
      return;
    }
    void loadCurrentInviteCode();
    return () => {
      inviteReadSeqRef.current += 1;
    };
  }, [canInviteMembers, inviteDialogOpen, loadCurrentInviteCode]);

  const handleGenerateInviteCode = useCallback(async () => {
    if (!canInviteMembers || inviteLoading || inviteReadLoading || inviteReadError) return;
    setInviteLoading(true);
    try {
      const result = await fetchResult<string>('/api/tenant/invite-code/generate?expiryDays=7', {
        method: 'post',
        token: token || undefined,
      });
      if (!ResultHelper.isSuccess(result) || typeof result.data !== 'string' || !result.data) {
        showErrorToast(inviteText('generateFailed'));
        return;
      }
      if (await loadCurrentInviteCode('generatedReadFailed', result.data)) {
        showSuccessToast(inviteText('generated'));
      }
    } catch {
      showErrorToast(inviteText('generateFailed'));
    } finally {
      setInviteLoading(false);
    }
  }, [
    canInviteMembers,
    inviteLoading,
    inviteReadError,
    inviteReadLoading,
    inviteText,
    loadCurrentInviteCode,
    showErrorToast,
    showSuccessToast,
    token,
  ]);

  const handleRevokeInviteCode = useCallback(async () => {
    if (
      !canInviteMembers ||
      !inviteCodeData?.code ||
      inviteLoading ||
      inviteReadLoading ||
      inviteReadError
    )
      return;
    setInviteLoading(true);
    try {
      const result = await fetchResult<boolean>(
        `/api/tenant/invite-code/revoke?code=${encodeURIComponent(inviteCodeData.code)}`,
        { method: 'post', token: token || undefined },
      );
      if (!ResultHelper.isSuccess(result) || result.data !== true) {
        showErrorToast(inviteText('revokeFailed'));
        return;
      }
      if (await loadCurrentInviteCode('revokedReadFailed')) {
        showSuccessToast(inviteText('revoked'));
      }
    } catch {
      showErrorToast(inviteText('revokeFailed'));
    } finally {
      setInviteLoading(false);
    }
  }, [
    canInviteMembers,
    inviteCodeData?.code,
    inviteLoading,
    inviteReadError,
    inviteReadLoading,
    inviteText,
    loadCurrentInviteCode,
    showErrorToast,
    showSuccessToast,
    token,
  ]);

  return { inviteText, loadCurrentInviteCode, handleGenerateInviteCode, handleRevokeInviteCode };
}
