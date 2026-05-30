/**
 * Semantic Dimension Picker — multi-select for dimensions declared in a
 * semantic model (PRD 16). Time dimensions expose a granularity dropdown whose
 * choice is encoded as the {@code code__grain} suffix the semantic compiler
 * understands (e.g. {@code order_date__month}); non-time dimensions encode as
 * the bare {@code code}.
 */

import React from 'react';
import { useSemanticModelMeta } from './useMetaModels';
import type { SemanticDimensionOption } from './types';

const GRAIN_SEP = '__';

/** Encode a dimension selection into the wire format the compiler expects. */
export function encodeDimension(code: string, grain?: string): string {
  return grain ? `${code}${GRAIN_SEP}${grain}` : code;
}

/** Split an encoded dimension value back into its base code and optional grain. */
export function decodeDimension(value: string): { code: string; grain?: string } {
  const idx = value.indexOf(GRAIN_SEP);
  if (idx === -1) return { code: value };
  return { code: value.slice(0, idx), grain: value.slice(idx + GRAIN_SEP.length) };
}

/** The currently selected encoded value for a dimension code, if any. */
export function selectedValueFor(value: string[], code: string): string | undefined {
  return value.find((v) => decodeDimension(v).code === code);
}

export interface SemanticDimensionPickerProps {
  semanticModelCode: string | undefined;
  /** Selected dimension values (encoded as code or code__grain) */
  value: string[];
  onChange: (values: string[]) => void;
  label?: string;
  className?: string;
}

export const SemanticDimensionPicker: React.FC<SemanticDimensionPickerProps> = ({
  semanticModelCode,
  value,
  onChange,
  label = '语义维度',
  className,
}) => {
  const { dimensions, isLoading } = useSemanticModelMeta(semanticModelCode);

  const renderLabel = () =>
    label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>;

  const toggle = (dim: SemanticDimensionOption, checked: boolean) => {
    const others = value.filter((v) => decodeDimension(v).code !== dim.code);
    if (!checked) {
      onChange(others);
      return;
    }
    const defaultGrain = dim.timeGrains && dim.timeGrains.length > 0 ? dim.timeGrains[0] : undefined;
    onChange([...others, encodeDimension(dim.code, defaultGrain)]);
  };

  const changeGrain = (dim: SemanticDimensionOption, grain: string) => {
    const others = value.filter((v) => decodeDimension(v).code !== dim.code);
    onChange([...others, encodeDimension(dim.code, grain)]);
  };

  if (!semanticModelCode) {
    return (
      <div className={className} data-testid="semantic-dimension-picker">
        {renderLabel()}
        <p className="text-sm text-gray-400">请先选择语义模型</p>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className={className} data-testid="semantic-dimension-picker">
        {renderLabel()}
        <p className="text-sm text-gray-500">加载维度中…</p>
      </div>
    );
  }

  return (
    <div className={className} data-testid="semantic-dimension-picker">
      {renderLabel()}
      {dimensions.length === 0 ? (
        <p className="text-sm text-gray-400">该语义模型暂无维度</p>
      ) : (
        <div className="max-h-40 overflow-y-auto rounded-md border border-gray-300 p-2">
          {dimensions.map((dim) => {
            const selected = selectedValueFor(value, dim.code);
            const checked = selected !== undefined;
            const grain = selected ? decodeDimension(selected).grain : undefined;
            const hasGrains = !!dim.timeGrains && dim.timeGrains.length > 0;
            return (
              <div key={dim.code} className="flex items-center gap-2 py-1 text-sm">
                <label className="flex cursor-pointer items-center gap-2">
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={(e) => toggle(dim, e.target.checked)}
                    className="rounded text-blue-600"
                  />
                  <span className="text-gray-800">{dim.name}</span>
                  <span className="text-xs text-gray-400">({dim.code})</span>
                </label>
                {checked && hasGrains && (
                  <select
                    aria-label={`${dim.code} 粒度`}
                    value={grain || dim.timeGrains![0]}
                    onChange={(e) => changeGrain(dim, e.target.value)}
                    className="rounded border border-gray-300 px-1 py-0.5 text-xs"
                  >
                    {dim.timeGrains!.map((g) => (
                      <option key={g} value={g}>
                        {g}
                      </option>
                    ))}
                  </select>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
