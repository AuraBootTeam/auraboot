import { useState, useMemo, useRef } from 'react';
import { useLocation, useNavigate as useRouterNavigate } from 'react-router';
import { getPublicRecordPid } from '~/framework/meta/utils/publicRecordId';
import { useTimezone } from '~/contexts/TimezoneContext';
import { useAuth } from '~/contexts/AuthContext';
import type { RecordData } from './detailPageTypes';
import { type PageContentProps } from '@auraboot/runtime-kernel';

type UseDetailPageStateArgs = { props: PageContentProps };

export function useDetailPageState({ props }: UseDetailPageStateArgs) {
  const { timezone } = useTimezone();
  const { schema, tableName, recordPid, token } = props;
  const pdfExportTargetRef = useRef<HTMLDivElement | null>(null);
  const { hasPermission } = useAuth();
  const location = useLocation();
  const routerNavigate = useRouterNavigate();
  const recordModelCode = schema?.modelCode || tableName;
  const routeRecordPid = useMemo(() => getPublicRecordPid({ pid: recordPid }) || '', [recordPid]);

  // Client-side record + model field loading (parallelized)
  const [recordData, setRecordData] = useState<RecordData>({});
  // Full raw API payload (before recordPath unwrap). For pages backed by a custom
  // extension.dataSource that returns an aggregate DTO (master + nested arrays),
  // dataPath blocks read their rows from here; recordData is the unwrapped master.
  const [rawData, setRawData] = useState<any>(null);
  const [recordLoading, setRecordLoading] = useState(true);
  const [recordError, setRecordError] = useState<string | null>(null);
  const [recordAccessDenied, setRecordAccessDenied] = useState(false);
  const [recordNotFound, setRecordNotFound] = useState(false);
  const [modelFieldMap, setModelFieldMap] = useState<Map<string, any>>(new Map());

  const hasApiSingletonRecordSource = Boolean(
    (schema as any)?.extension?.dataSource?.type === 'api' &&
    typeof (schema as any)?.extension?.dataSource?.endpoint === 'string' &&
    (schema as any)?.extension?.dataSource?.endpoint,
  );
  return {
    timezone,
    schema,
    tableName,
    recordPid,
    token,
    pdfExportTargetRef,
    hasPermission,
    location,
    routerNavigate,
    recordModelCode,
    routeRecordPid,
    recordData,
    setRecordData,
    rawData,
    setRawData,
    recordLoading,
    setRecordLoading,
    recordError,
    setRecordError,
    recordAccessDenied,
    setRecordAccessDenied,
    recordNotFound,
    setRecordNotFound,
    modelFieldMap,
    setModelFieldMap,
    hasApiSingletonRecordSource,
  };
}
