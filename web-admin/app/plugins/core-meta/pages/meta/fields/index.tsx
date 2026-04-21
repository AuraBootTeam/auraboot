import React, { useMemo, useState } from 'react';
import { useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/shared/services/session';
import { ListPageContent } from '~/framework/meta/rendering/pages/ListPageContent';
import { useSchemaLoader } from '~/framework/meta/hooks/useSchemaLoader';
import { useBatchResourceOwners } from '~/hooks/useResourceOwner';
import type { MetaFieldDTO } from '~/types/fieldLibrary';
import {
  FIELD_LIST_RELOAD_EVENT,
  FieldListSchemaProvider,
} from './FieldListSchemaContext';
import { ErrorAlert } from '~/ui/ErrorAlert';
import { LoadingSpinner } from '~/ui/LoadingSpinner';

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const token = await getTokenFromRequest(request);
  return { token };
};

export default function FieldLibraryPage() {
  const { token } = useLoaderData<typeof loader>();
  const [records, setRecords] = useState<MetaFieldDTO[]>([]);
  const { schema, loading, error } = useSchemaLoader({
    pageKey: 'meta_fields_admin',
    token: token ?? undefined,
  });

  const resourceRefs = useMemo(
    () =>
      records
        .filter((record) => !!record.code)
        .map((record) => ({ type: 'field', code: record.code })),
    [records],
  );
  const { owners } = useBatchResourceOwners(resourceRefs.length > 0 ? resourceRefs : null);

  const listExtensions = useMemo(
    () => ({
      onDataChange: (rows: Record<string, any>[]) => setRecords(rows as MetaFieldDTO[]),
      disableRowClick: true,
      disableRowSelection: true,
      hideBuiltInImport: true,
      hideBuiltInExport: true,
      hideBuiltInPrint: true,
      hideSavedViews: true,
      reloadEventName: FIELD_LIST_RELOAD_EVENT,
    }),
    [],
  );

  const contextValue = useMemo(
    () => ({
      owners,
      reloadEventName: FIELD_LIST_RELOAD_EVENT,
    }),
    [owners],
  );

  if (loading || !schema) {
    if (error) {
      return <ErrorAlert error={error.message} />;
    }
    return <LoadingSpinner />;
  }

  return (
    <FieldListSchemaProvider value={contextValue}>
      <ListPageContent
        schema={schema}
        tableName="meta_fields_admin"
        token={token}
        listExtensions={listExtensions}
      />
    </FieldListSchemaProvider>
  );
}
