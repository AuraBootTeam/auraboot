/**
 * Runtime renderer for header/footer bands
 */

import React from 'react';
import { ReportBandBlock } from '../blocks/ReportBandBlock';
import type { ReportBand } from '../types';

interface ReportBandRendererProps {
  band: ReportBand;
  position: 'header' | 'footer';
}

export const ReportBandRenderer: React.FC<ReportBandRendererProps> = ({ band, position }) => {
  return <ReportBandBlock band={band} mode="runtime" position={position} />;
};
