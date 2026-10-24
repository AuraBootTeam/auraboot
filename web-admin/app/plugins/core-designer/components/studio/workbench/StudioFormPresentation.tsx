import React, { createContext, useEffect, useState } from 'react';
import { resolveFormPresentation } from '~/framework/meta/rendering/pages/formPage/formPresentation';
import { loadModelFieldsByModelCodes } from '../../unified-designer/persistence/modelFieldsRepository';
import type { ModelFieldDefinition } from '../../unified-designer/types';
import type { PageSchema } from '../domain/dsl/types';

export const StudioFormPresentationContext = createContext({
  presentation: resolveFormPresentation(),
  fields: [] as ModelFieldDefinition[],
});

/** Uses the existing metadata repository; preview enrichment never changes stored DSL. */
export function StudioFormPresentationProvider({
  schema,
  children,
}: {
  schema: PageSchema | null;
  children: React.ReactNode;
}) {
  const [fields, setFields] = useState<ModelFieldDefinition[]>([]);
  const modelCode = schema?.modelCode;
  useEffect(() => {
    let current = true;
    setFields([]);
    if (modelCode)
      void loadModelFieldsByModelCodes([modelCode])
        .then((result) => {
          if (current) setFields(result[modelCode] ?? []);
        })
        .catch(() => {
          if (current) setFields([]);
        });
    return () => {
      current = false;
    };
  }, [modelCode]);
  return (
    <StudioFormPresentationContext.Provider
      value={{ presentation: resolveFormPresentation(schema?.extension), fields }}
    >
      {children}
    </StudioFormPresentationContext.Provider>
  );
}
