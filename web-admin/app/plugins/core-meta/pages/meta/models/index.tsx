import React, { useMemo, useState } from 'react';
import { ListPageContent } from '~/framework/meta/rendering/pages/ListPageContent';
import { useSchemaLoader } from '~/framework/meta/hooks/useSchemaLoader';
import { useBatchResourceOwners } from '~/hooks/useResourceOwner';
import type { MetaModelDTO } from '~/types/model';
import {
  MODEL_LIST_RELOAD_EVENT,
  ModelListSchemaProvider,
} from './ListSchemaContext';
import { ErrorAlert } from '~/ui/ErrorAlert';
import { LoadingSpinner } from '~/ui/LoadingSpinner';

export const loader = async () => ({});

export default function ModelListPage() {
  const [records, setRecords] = useState<MetaModelDTO[]>([]);
  const { schema, loading, error } = useSchemaLoader({
    pageKey: 'meta_models_admin',
    // ships without a DSL page; a missing schema is the normal case, not an error
    optional: true,
  });

  const resourceRefs = useMemo(
    () =>
      records
        .filter((record) => !!record.code)
        .map((record) => ({ type: 'MODEL', code: record.code })),
    [records],
  );
  const { owners } = useBatchResourceOwners(resourceRefs.length > 0 ? resourceRefs : null);

  const listExtensions = useMemo(
    () => ({
      onDataChange: (rows: Record<string, any>[]) => setRecords(rows as MetaModelDTO[]),
      disableRowClick: true,
      disableRowSelection: true,
      hideBuiltInImport: true,
      hideBuiltInExport: true,
      hideBuiltInPrint: true,
      hideSavedViews: true,
      reloadEventName: MODEL_LIST_RELOAD_EVENT,
    }),
    [],
  );

  const contextValue = useMemo(
    () => ({
      owners,
      reloadEventName: MODEL_LIST_RELOAD_EVENT,
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
    <ModelListSchemaProvider value={contextValue}>
      <ListPageContent
        schema={schema}
        tableName="meta_models_admin"
        listExtensions={listExtensions}
      />
    </ModelListSchemaProvider>
  );
}
