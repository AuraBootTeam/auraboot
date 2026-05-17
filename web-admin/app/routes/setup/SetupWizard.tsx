import { useState, useEffect } from 'react';
import { useNavigate, Link, useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { Button } from '~/ui/ui/button';
import { fetchBootstrapStatus, type BootstrapStatus } from '~/services/bootstrapStatus';
import { bootstrapT } from '~/services/bootstrapTexts';

/**
 * Loader: fetch bootstrap status; UI branches between wizard and "already done" page.
 */
export async function loader(_args: LoaderFunctionArgs): Promise<{ status: BootstrapStatus | null }> {
  const status = await fetchBootstrapStatus();
  return { status };
}

export default function SetupWizard() {
  const { status } = useLoaderData<typeof loader>();
  const navigate = useNavigate();
  const [formData, setFormData] = useState({
    companyName: '',
    adminEmail: '',
    adminPassword: '',
    confirmPassword: '',
    systemMode: 'single',
  });
  const [phase, setPhase] = useState<'form' | 'progress' | 'done' | 'error'>('form');
  const [progress, setProgress] = useState({
    completedSteps: 0,
    totalSteps: 9,
    currentStep: '',
    status: '',
  });
  const [formError, setFormError] = useState('');
  const [setupError, setSetupError] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);

  // Poll progress while in progress phase
  useEffect(() => {
    if (phase !== 'progress') return;
    const interval = setInterval(async () => {
      try {
        const res = await fetch('/api/bootstrap/progress');
        const result = await res.json();
        if (result.code === '0' && result.data) {
          setProgress(result.data);
        }
      } catch {
        // ignore polling errors
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [phase]);

  if (status?.initialized) {
    return (
      <div data-testid="bootstrap-already-done" className="max-w-md mx-auto mt-20 p-6 bg-white border border-gray-200 rounded shadow">
        <h1 className="text-xl font-semibold mb-2 text-gray-900">{bootstrapT('alreadyDoneTitle')}</h1>
        <p className="text-gray-600 mb-4">{bootstrapT('alreadyDoneBody')}</p>
        <Link to="/" className="inline-block px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700">
          {bootstrapT('alreadyDoneCta')}
        </Link>
      </div>
    );
  }

  const handleChange = (field: string, value: string | boolean) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    setFormError('');
  };

  const handleSubmit = async () => {
    setFormError('');

    if (!formData.adminEmail.trim()) {
      setFormError('Email is required');
      return;
    }
    if (!formData.adminPassword) {
      setFormError('Password is required');
      return;
    }
    if (formData.adminPassword.length < 8) {
      setFormError('Password must be at least 8 characters');
      return;
    }
    if (formData.adminPassword !== formData.confirmPassword) {
      setFormError('Passwords do not match');
      return;
    }

    setPhase('progress');

    try {
      const res = await fetch('/api/bootstrap/setup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          companyName: formData.companyName || 'My Company',
          adminEmail: formData.adminEmail,
          adminPassword: formData.adminPassword,
          systemMode: formData.systemMode,
        }),
      });
      const result = await res.json();

      if (result.code === '0' && result.data?.success) {
        setPhase('done');
        setTimeout(() => navigate('/login'), 3000);
      } else {
        setSetupError(result.message || 'Setup failed');
        setPhase('error');
      }
    } catch {
      setSetupError('Connection failed. Is the backend running?');
      setPhase('error');
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100 p-4 dark:from-gray-900 dark:to-gray-800">
      <div className="w-full max-w-lg">
        {/* Logo */}
        <div className="mb-8 text-center">
          <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-xl bg-blue-600 text-2xl font-bold text-white shadow-lg">
            A
          </div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Welcome to AuraBoot</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Set up your platform in a few seconds
          </p>
        </div>

        {/* Card */}
        <div className="rounded-xl border border-gray-200 bg-white p-8 shadow-sm dark:border-gray-700 dark:bg-gray-800">
          {phase === 'form' && (
            <div className="space-y-5">
              {/* Company Name */}
              <div>
                <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Company Name
                </label>
                <input
                  type="text"
                  value={formData.companyName}
                  onChange={(e) => handleChange('companyName', e.target.value)}
                  placeholder="My Company"
                  className="w-full rounded-lg border border-gray-300 px-3.5 py-2.5 text-sm shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
              </div>

              {/* Admin Email */}
              <div>
                <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Admin Email <span className="text-red-500">*</span>
                </label>
                <input
                  type="email"
                  value={formData.adminEmail}
                  onChange={(e) => handleChange('adminEmail', e.target.value)}
                  placeholder="admin@company.com"
                  className="w-full rounded-lg border border-gray-300 px-3.5 py-2.5 text-sm shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
              </div>

              {/* Password */}
              <div>
                <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Password <span className="text-red-500">*</span>
                </label>
                <input
                  type="password"
                  value={formData.adminPassword}
                  onChange={(e) => handleChange('adminPassword', e.target.value)}
                  placeholder="At least 8 characters"
                  className="w-full rounded-lg border border-gray-300 px-3.5 py-2.5 text-sm shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
              </div>

              {/* Confirm Password */}
              <div>
                <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Confirm Password <span className="text-red-500">*</span>
                </label>
                <input
                  type="password"
                  value={formData.confirmPassword}
                  onChange={(e) => handleChange('confirmPassword', e.target.value)}
                  placeholder="Re-enter your password"
                  className="w-full rounded-lg border border-gray-300 px-3.5 py-2.5 text-sm shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
              </div>

              {/* Advanced Settings */}
              <div>
                <button
                  type="button"
                  onClick={() => setShowAdvanced(!showAdvanced)}
                  className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
                >
                  <span
                    className={`transform transition-transform ${showAdvanced ? 'rotate-90' : ''}`}
                  >
                    &#9654;
                  </span>
                  Advanced Settings
                </button>
                {showAdvanced && (
                  <div className="mt-3 rounded-lg border border-gray-200 bg-gray-50 p-4 dark:border-gray-600 dark:bg-gray-700/50">
                    <label className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300">
                      System Mode
                    </label>
                    <div className="space-y-2">
                      {[
                        {
                          value: 'single',
                          label: 'Single Tenant',
                          desc: 'Internal use, one organization',
                        },
                        {
                          value: 'multi',
                          label: 'Multi Tenant',
                          desc: 'SaaS platform, multiple organizations',
                        },
                        { value: 'hybrid', label: 'Hybrid', desc: 'Managed hosting + marketplace' },
                      ].map((opt) => (
                        <label key={opt.value} className="flex cursor-pointer items-start gap-3">
                          <input
                            type="radio"
                            name="systemMode"
                            value={opt.value}
                            checked={formData.systemMode === opt.value}
                            onChange={(e) => handleChange('systemMode', e.target.value)}
                            className="mt-0.5 h-4 w-4 border-gray-300 text-blue-600 focus:ring-blue-500"
                          />
                          <div>
                            <div className="text-sm font-medium text-gray-700 dark:text-gray-300">
                              {opt.label}
                            </div>
                            <div className="text-xs text-gray-400">{opt.desc}</div>
                          </div>
                        </label>
                      ))}
                    </div>
                  </div>
                )}
              </div>

              {/* Error */}
              {formError && (
                <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/30 dark:text-red-400">
                  {formError}
                </div>
              )}

              {/* Submit */}
              <Button onClick={handleSubmit} className="w-full" size="lg">
                Launch AuraBoot
              </Button>
            </div>
          )}

          {phase === 'progress' && (
            <div className="space-y-6 py-4 text-center">
              <div className="mx-auto h-12 w-12 animate-spin rounded-full border-4 border-blue-200 border-t-blue-600" />
              <div>
                <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                  Setting up your platform...
                </h2>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                  {progress.currentStep || 'Initializing...'}
                </p>
              </div>
              {/* Progress bar */}
              <div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                  <div
                    className="h-full rounded-full bg-blue-600 transition-all duration-500"
                    style={{
                      width: `${Math.round((progress.completedSteps / progress.totalSteps) * 100)}%`,
                    }}
                  />
                </div>
                <p className="mt-2 text-xs text-gray-400">
                  {progress.completedSteps} / {progress.totalSteps} steps
                </p>
              </div>
            </div>
          )}

          {phase === 'done' && (
            <div className="space-y-4 py-4 text-center">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-green-100 text-2xl text-green-600 dark:bg-green-900/30">
                &#10003;
              </div>
              <div>
                <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                  Setup Complete!
                </h2>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                  Redirecting to login...
                </p>
              </div>
            </div>
          )}

          {phase === 'error' && (
            <div className="space-y-4 py-4 text-center">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-red-100 text-2xl text-red-600 dark:bg-red-900/30">
                &#10007;
              </div>
              <div>
                <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                  Setup Failed
                </h2>
                <p className="mt-1 text-sm text-red-600 dark:text-red-400">{setupError}</p>
              </div>
              <Button onClick={() => setPhase('form')} variant="outline">
                Try Again
              </Button>
            </div>
          )}
        </div>

        <p className="mt-6 text-center text-xs text-gray-400">
          AuraBoot Platform &mdash; AI-Native SaaS Control Plane
        </p>
      </div>
    </div>
  );
}
