import { useState, useEffect } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { useDetailPageState } from './useDetailPageState';

type UseDetailSharingArgs = Pick<
  ReturnType<typeof useDetailPageState>,
  'schema' | 'recordModelCode' | 'routeRecordPid' | 'token'
>;

export function useDetailSharing({
  schema,
  recordModelCode,
  routeRecordPid,
  token,
}: UseDetailSharingArgs) {
  const [shareDialogOpen, setShareDialogOpen] = useState(false);
  const [canManageRecordShares, setCanManageRecordShares] = useState(false);

  useEffect(() => {
    if (schema?.extension?.showShare === false || !recordModelCode || !routeRecordPid) {
      setCanManageRecordShares(false);
      return;
    }
    let cancelled = false;
    const params = new URLSearchParams({
      resourceCode: recordModelCode,
      recordPid: routeRecordPid,
    });
    fetchResult<{ canManage?: boolean }>(
      `/api/record-share/manage-capability?${params.toString()}`,
      { method: 'get', token: token || undefined },
    )
      .then((result) => {
        if (!cancelled) {
          setCanManageRecordShares(
            ResultHelper.isSuccess(result) && result.data?.canManage === true,
          );
        }
      })
      .catch(() => {
        if (!cancelled) setCanManageRecordShares(false);
      });
    return () => {
      cancelled = true;
    };
  }, [recordModelCode, routeRecordPid, schema?.extension?.showShare, token]);
  return { shareDialogOpen, setShareDialogOpen, canManageRecordShares };
}
