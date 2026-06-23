import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ReportGenerateButton } from '../ReportGenerateButton';

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showErrorToast: vi.fn(),
    showInfoToast: vi.fn(),
    showSuccessToast: vi.fn(),
    showToast: vi.fn(),
    showWarningToast: vi.fn(),
  }),
}));

vi.mock('~/shared/services/reportTemplateService', () => ({
  reportTemplateService: {
    generate: vi.fn(),
    getPublished: vi.fn(),
  },
}));

describe('ReportGenerateButton', () => {
  it('uses a Chinese fallback label when report.generate is not translated', () => {
    render(<ReportGenerateButton modelCode="crm_lead_common" recordPid="lead-1" />);

    expect(screen.getByTestId('report-generate-button')).toHaveTextContent('报告');
    expect(screen.getByTestId('report-generate-button')).not.toHaveTextContent('Report');
  });
});
