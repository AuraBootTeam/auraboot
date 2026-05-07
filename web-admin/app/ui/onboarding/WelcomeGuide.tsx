/**
 * WelcomeGuide — Dismissable onboarding banner for new users.
 *
 * Shown at the top of the dashboard. Stores dismissal state in localStorage.
 */

import { useState, useEffect } from 'react';
import { Link } from 'react-router';
import {
  XMarkIcon,
  SparklesIcon,
  PaintBrushIcon,
  RocketLaunchIcon,
} from '@heroicons/react/24/outline';

const STORAGE_KEY = 'onboarding_dismissed';

export function WelcomeGuide() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    // Only show if not dismissed
    const dismissed = localStorage.getItem(STORAGE_KEY);
    if (!dismissed) {
      setVisible(true);
    }
  }, []);

  if (!visible) return null;

  const dismiss = () => {
    localStorage.setItem(STORAGE_KEY, '1');
    setVisible(false);
  };

  return (
    <div className="relative mb-6 overflow-hidden rounded-2xl bg-gradient-to-br from-blue-600 via-indigo-600 to-violet-600 text-white shadow-lg">
      {/* Background pattern */}
      <div className="absolute inset-0 opacity-10">
        <div className="absolute -top-24 -right-24 h-96 w-96 rounded-full bg-white/20" />
        <div className="absolute -bottom-16 -left-16 h-64 w-64 rounded-full bg-white/10" />
      </div>

      {/* Dismiss */}
      <button
        onClick={dismiss}
        className="absolute top-4 right-4 z-10 rounded-lg p-1.5 transition-colors hover:bg-white/20"
        aria-label="Dismiss welcome guide"
      >
        <XMarkIcon className="h-5 w-5" />
      </button>

      <div className="relative p-6 sm:p-8">
        {/* Title */}
        <div className="mb-6">
          <h2 className="mb-2 text-2xl font-bold">Welcome to AuraBoot</h2>
          <p className="max-w-xl text-sm text-blue-100">
            Your no-code business platform is ready. Start with a template, explore existing
            features, or build something entirely custom.
          </p>
        </div>

        {/* Action cards */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Link
            to="/plugins"
            className="group flex items-start gap-3 rounded-xl bg-white/10 p-4 backdrop-blur-sm transition-all hover:bg-white/20"
          >
            <div className="rounded-lg bg-white/20 p-2 transition-colors group-hover:bg-white/30">
              <RocketLaunchIcon className="h-5 w-5" />
            </div>
            <div>
              <h3 className="mb-0.5 text-sm font-semibold">Browse Plugins</h3>
              <p className="text-xs leading-relaxed text-blue-100">
                Install business apps and extensions from the plugin catalog.
              </p>
            </div>
          </Link>

          <Link
            to="/dashboards"
            className="group flex items-start gap-3 rounded-xl bg-white/10 p-4 backdrop-blur-sm transition-all hover:bg-white/20"
          >
            <div className="rounded-lg bg-white/20 p-2 transition-colors group-hover:bg-white/30">
              <SparklesIcon className="h-5 w-5" />
            </div>
            <div>
              <h3 className="mb-0.5 text-sm font-semibold">Explore Dashboards</h3>
              <p className="text-xs leading-relaxed text-blue-100">
                View analytics dashboards and real-time insights.
              </p>
            </div>
          </Link>

          <Link
            to="/page-designer"
            className="group flex items-start gap-3 rounded-xl bg-white/10 p-4 backdrop-blur-sm transition-all hover:bg-white/20"
          >
            <div className="rounded-lg bg-white/20 p-2 transition-colors group-hover:bg-white/30">
              <PaintBrushIcon className="h-5 w-5" />
            </div>
            <div>
              <h3 className="mb-0.5 text-sm font-semibold">Build Custom</h3>
              <p className="text-xs leading-relaxed text-blue-100">
                Use the Page Designer to create your own data-driven pages.
              </p>
            </div>
          </Link>
        </div>
      </div>
    </div>
  );
}
