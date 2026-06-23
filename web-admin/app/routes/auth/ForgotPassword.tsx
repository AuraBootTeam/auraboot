import { Link } from 'react-router';

export default function ForgotPassword() {
  return (
    <div
      className="flex min-h-screen items-center justify-center bg-gray-50"
      data-testid="forgot-password-disabled"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-8 text-center shadow-md">
        <h2 className="mb-2 text-2xl font-semibold text-gray-900">Password reset unavailable</h2>
        <p className="mb-6 text-gray-600">
          Contact your tenant administrator to set or reset your password.
        </p>
        <Link
          to="/login"
          className="inline-block rounded-md bg-blue-600 px-6 py-2 text-white transition-colors hover:bg-blue-700"
        >
          Back to Login
        </Link>
      </div>
    </div>
  );
}
