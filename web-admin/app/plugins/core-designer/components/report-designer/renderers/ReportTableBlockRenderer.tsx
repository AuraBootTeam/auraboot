/**
 * Runtime renderer for table blocks
 */

import React from 'react';
import { ReportTableBlock } from '../blocks/ReportTableBlock';
import type { DataTableBlock } from '../types';

interface ReportTableBlockRendererProps {
  block: DataTableBlock;
  data: Record<string, unknown>[];
}

export const ReportTableBlockRenderer: React.FC<ReportTableBlockRendererProps> = ({
  block,
  data,
}) => {
  return <ReportTableBlock block={block} mode="runtime" data={data} />;
};
