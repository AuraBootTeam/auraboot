import { useState, useRef } from 'react';
import { Link } from 'react-router';
import { MegaMenu } from './MegaMenu';
import { NAV_CONFIG } from './MegaMenuData';

interface MarketingHeaderProps {
  isLoggedIn?: boolean;
}

export function MarketingHeader({ isLoggedIn = false }: MarketingHeaderProps) {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [activeMenu, setActiveMenu] = useState<string | null>(null);
  const [mobileExpanded, setMobileExpanded] = useState<string | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout>>();

  const handleMouseEnter = (label: string) => {
    clearTimeout(timeoutRef.current);
    setActiveMenu(label);
  };

  const handleMouseLeave = () => {
    timeoutRef.current = setTimeout(() => setActiveMenu(null), 150);
  };

  return (
    <header className="marketing-header fixed top-0 left-0 right-0 z-50">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-16 items-center justify-between">
          {/* Logo */}
          <Link to="/" className="text-xl font-bold text-gray-900">
            AuraBoot
          </Link>

          {/* Desktop nav */}
          <nav className="hidden md:flex items-center gap-1">
            {NAV_CONFIG.map((item) => (
              <div
                key={item.label}
                className="relative"
                onMouseEnter={() => item.columns && handleMouseEnter(item.label)}
                onMouseLeave={item.columns ? handleMouseLeave : undefined}
              >
                {item.href ? (
                  <Link
                    to={item.href}
                    className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-gray-600 hover:text-gray-900 transition-colors cursor-pointer rounded-lg hover:bg-gray-50"
                  >
                    {item.label}
                  </Link>
                ) : (
                  <button
                    type="button"
                    className={`flex items-center gap-1 px-3 py-2 text-sm font-medium transition-colors cursor-pointer rounded-lg ${
                      activeMenu === item.label ? 'text-gray-900 bg-gray-50' : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'
                    }`}
                  >
                    {item.label}
                    <svg
                      className={`h-4 w-4 transition-transform duration-200 ${activeMenu === item.label ? 'rotate-180' : ''}`}
                      fill="none" viewBox="0 0 24 24" stroke="currentColor"
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                    </svg>
                  </button>
                )}
              </div>
            ))}
          </nav>

          {/* Desktop right side */}
          <div className="hidden md:flex items-center gap-4">
            {isLoggedIn ? (
              <Link
                to="/meta/models"
                className="rounded-lg bg-purple-600 px-4 py-2 text-sm font-medium text-white hover:bg-purple-700 transition-colors cursor-pointer"
              >
                Go to App &rarr;
              </Link>
            ) : (
              <>
                <Link
                  to="/login"
                  className="text-sm font-medium text-gray-600 hover:text-gray-900 transition-colors cursor-pointer"
                >
                  Login
                </Link>
                <Link
                  to="/register"
                  className="rounded-lg bg-purple-600 px-4 py-2 text-sm font-medium text-white hover:bg-purple-700 transition-colors cursor-pointer"
                >
                  Get Started &rarr;
                </Link>
              </>
            )}
          </div>

          {/* Mobile hamburger */}
          <button
            type="button"
            className="md:hidden text-gray-600 cursor-pointer"
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            aria-label="Toggle menu"
          >
            {mobileMenuOpen ? (
              <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            ) : (
              <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            )}
          </button>
        </div>
      </div>

      {/* Desktop mega menu dropdown — single panel, content crossfades */}
      {activeMenu && (() => {
        const active = NAV_CONFIG.find(i => i.label === activeMenu && i.columns);
        if (!active?.columns) return null;
        return (
          <MegaMenu
            columns={active.columns}
            onMouseEnter={() => handleMouseEnter(active.label)}
            onMouseLeave={handleMouseLeave}
          />
        );
      })()}

      {/* Mobile menu */}
      {mobileMenuOpen && (
        <div className="md:hidden bg-white border-t border-gray-100 shadow-lg">
          <div className="px-4 py-4 space-y-1">
            {NAV_CONFIG.map((item) =>
              item.href ? (
                <Link
                  key={item.label}
                  to={item.href}
                  className="block px-3 py-2 text-sm font-medium text-gray-900 hover:text-purple-600 hover:bg-gray-50 rounded-lg transition-colors cursor-pointer"
                  onClick={() => setMobileMenuOpen(false)}
                >
                  {item.label}
                </Link>
              ) : (
                <div key={item.label}>
                  <button
                    type="button"
                    className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-900 hover:bg-gray-50 rounded-lg transition-colors cursor-pointer"
                    onClick={() => setMobileExpanded(mobileExpanded === item.label ? null : item.label)}
                  >
                    {item.label}
                    <svg
                      className={`h-4 w-4 text-gray-400 transition-transform duration-150 ${mobileExpanded === item.label ? 'rotate-180' : ''}`}
                      fill="none" viewBox="0 0 24 24" stroke="currentColor"
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                    </svg>
                  </button>
                  {mobileExpanded === item.label && item.columns && (
                    <div className="mt-1 ml-3 space-y-3 pb-2">
                      {item.columns.map((col) => (
                        <div key={col.title}>
                          <p className="px-3 py-1 text-xs font-semibold uppercase tracking-wider text-gray-400">
                            {col.title}
                          </p>
                          {col.items.map((sub) =>
                            sub.external ? (
                              <a
                                key={sub.title}
                                href={sub.href}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="block px-3 py-1.5 text-sm text-gray-600 hover:text-purple-600 transition-colors cursor-pointer"
                                onClick={() => setMobileMenuOpen(false)}
                              >
                                {sub.title}
                              </a>
                            ) : (
                              <Link
                                key={sub.title}
                                to={sub.href}
                                className="block px-3 py-1.5 text-sm text-gray-600 hover:text-purple-600 transition-colors cursor-pointer"
                                onClick={() => setMobileMenuOpen(false)}
                              >
                                {sub.title}
                              </Link>
                            )
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )
            )}
            <div className="border-t border-gray-100 pt-3 space-y-2">
              {isLoggedIn ? (
                <Link
                  to="/meta/models"
                  className="block rounded-lg bg-purple-600 px-4 py-2 text-center text-sm font-medium text-white hover:bg-purple-700 transition-colors cursor-pointer"
                  onClick={() => setMobileMenuOpen(false)}
                >
                  Go to App &rarr;
                </Link>
              ) : (
                <>
                  <Link
                    to="/login"
                    className="block px-3 py-2 text-sm font-medium text-gray-900 hover:text-purple-600 transition-colors cursor-pointer"
                    onClick={() => setMobileMenuOpen(false)}
                  >
                    Login
                  </Link>
                  <Link
                    to="/register"
                    className="block rounded-lg bg-purple-600 px-4 py-2 text-center text-sm font-medium text-white hover:bg-purple-700 transition-colors cursor-pointer"
                    onClick={() => setMobileMenuOpen(false)}
                  >
                    Get Started &rarr;
                  </Link>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </header>
  );
}
