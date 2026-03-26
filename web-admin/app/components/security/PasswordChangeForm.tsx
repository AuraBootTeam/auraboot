import { useState } from 'react';
import { put } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToast } from '~/contexts/ToastContext';
import PasswordStrengthIndicator from './PasswordStrengthIndicator';

interface PasswordChangeFormProps {
  onSuccess?: () => void;
}

export default function PasswordChangeForm({ onSuccess }: PasswordChangeFormProps) {
  const { showSuccessToast, showErrorToast } = useToast();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!currentPassword) newErrors.currentPassword = 'Please enter current password';
    if (!newPassword) newErrors.newPassword = 'Please enter new password';
    if (newPassword.length < 8) newErrors.newPassword = 'Password must be at least 8 characters';
    if (newPassword !== confirmPassword) newErrors.confirmPassword = 'Passwords do not match';
    if (newPassword === currentPassword)
      newErrors.newPassword = 'New password must be different from current password';
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsSubmitting(true);
    try {
      const result = await put<{ code: string; message?: string }>('/api/user/password', {
        currentPassword,
        newPassword,
        confirmPassword,
      });
      if (result && ResultHelper.isSuccess(result)) {
        showSuccessToast('Password changed successfully. Please log in again.');
        setCurrentPassword('');
        setNewPassword('');
        setConfirmPassword('');
        setErrors({});
        onSuccess?.();
        // Redirect to login after a brief delay since tokens are invalidated
        setTimeout(() => {
          window.location.href = '/login';
        }, 1500);
      } else {
        showErrorToast(result?.message || 'Failed to change password');
      }
    } catch (error) {
      showErrorToast(error instanceof Error ? error.message : 'Failed to change password');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="max-w-md space-y-4">
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Current Password</label>
        <input
          type="password"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
          data-testid="current-password-input"
        />
        {errors.currentPassword && (
          <p className="mt-1 text-sm text-red-600">{errors.currentPassword}</p>
        )}
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">New Password</label>
        <input
          type="password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
          data-testid="new-password-input"
        />
        <PasswordStrengthIndicator password={newPassword} />
        {errors.newPassword && <p className="mt-1 text-sm text-red-600">{errors.newPassword}</p>}
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Confirm New Password</label>
        <input
          type="password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
          data-testid="confirm-password-input"
        />
        {errors.confirmPassword && (
          <p className="mt-1 text-sm text-red-600">{errors.confirmPassword}</p>
        )}
      </div>

      <button
        type="submit"
        disabled={isSubmitting}
        className="rounded-md bg-blue-600 px-6 py-2 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        data-testid="change-password-btn"
      >
        {isSubmitting ? 'Changing...' : 'Change Password'}
      </button>
    </form>
  );
}
