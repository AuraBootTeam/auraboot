import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import QrCodeScanner from '../QrCodeScanner';

// QrCodeScanner's labels/buttons were hardcoded Chinese; they now go through
// useSmartText('$i18n:qr_scanner.*', '<English fallback>'). With no i18n provider in the
// test env, st() returns the English fallback.
vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('~/contexts/ToastContext', () => ({ useToast: () => ({ showErrorToast: vi.fn() }) }));

describe('QrCodeScanner i18n', () => {
  it('renders localized title and action buttons (English fallback, no Chinese)', () => {
    render(<QrCodeScanner isOpen onClose={vi.fn()} />);
    expect(screen.getByText('Scan device QR code')).toBeInTheDocument();
    expect(screen.getByText('Scan QR code')).toBeInTheDocument();
    expect(screen.getByText('Manual input')).toBeInTheDocument();
    // no raw i18n key leak
    expect(screen.queryByText(/qr_scanner\./)).not.toBeInTheDocument();
  });
});
