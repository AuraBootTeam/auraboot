import React, { createContext, useContext } from 'react';
import type { ResourceOwnerInfo } from '~/hooks/useResourceOwner';

export const FIELD_LIST_RELOAD_EVENT = 'meta-field-list:reload';

interface FieldListSchemaContextValue {
  owners: Record<string, ResourceOwnerInfo>;
  reloadEventName: string;
}

const FieldListSchemaContext = createContext<FieldListSchemaContextValue>({
  owners: {},
  reloadEventName: FIELD_LIST_RELOAD_EVENT,
});

export function FieldListSchemaProvider({
  value,
  children,
}: {
  value: FieldListSchemaContextValue;
  children: React.ReactNode;
}) {
  return (
    <FieldListSchemaContext.Provider value={value}>
      {children}
    </FieldListSchemaContext.Provider>
  );
}

export function useFieldListSchemaContext() {
  return useContext(FieldListSchemaContext);
}
