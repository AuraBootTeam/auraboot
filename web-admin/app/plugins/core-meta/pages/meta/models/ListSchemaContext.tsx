import React, { createContext, useContext } from 'react';
import type { ResourceOwnerInfo } from '~/hooks/useResourceOwner';

export const MODEL_LIST_RELOAD_EVENT = 'meta-model-list:reload';

interface ModelListSchemaContextValue {
  owners: Record<string, ResourceOwnerInfo>;
  reloadEventName: string;
}

const ModelListSchemaContext = createContext<ModelListSchemaContextValue>({
  owners: {},
  reloadEventName: MODEL_LIST_RELOAD_EVENT,
});

export function ModelListSchemaProvider({
  value,
  children,
}: {
  value: ModelListSchemaContextValue;
  children: React.ReactNode;
}) {
  return (
    <ModelListSchemaContext.Provider value={value}>
      {children}
    </ModelListSchemaContext.Provider>
  );
}

export function useModelListSchemaContext() {
  return useContext(ModelListSchemaContext);
}
