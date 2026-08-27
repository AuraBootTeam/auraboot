import React, { useState, useEffect, useRef } from 'react';
import { Link } from 'react-router';
import {
  BuildingOffice2Icon,
  Cog6ToothIcon,
  InformationCircleIcon,
  IdentificationIcon,
  PowerIcon,
} from '@heroicons/react/24/outline';
import { useRootLoaderData } from '~/root-data';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { useI18n } from '~/contexts/I18nContext';
import { useSmartText } from '~/utils/i18n';

interface UserMenuWidgetProps {
  /** Skip identity/workspace lookups (compact surfaces like tenant selection). */
  simplified?: boolean;
}

interface PartyActorOption {
  partyId: string;
  partyMembershipId: string;
  displayName: string;
  partyType: string;
  lifecycleStatus: string;
  membershipStatus: string;
  current: boolean;
}

/**
 * Account entry point (avatar + profile / workspace / logout menu).
 *
 * Lives outside the <header> toolbar: the widget is pinned to the
 * bottom-start corner of the viewport (bottom-left in LTR, bottom-right in
 * RTL) and its menu opens upward from the corner.
 */
export function UserMenuWidget({ simplified = false }: UserMenuWidgetProps) {
  const rootData = useRootLoaderData();
  const user = rootData?.user ?? null;
  const showBusinessWorkspaceSwitcher = rootData?.accessPolicy?.deploymentMode !== 'single';
  const branding = rootData?.branding ?? COMMUNITY_BRANDING;
  const { t, locale } = useI18n();
  const st = useSmartText();

  const [showUserDropdown, setShowUserDropdown] = useState(false);
  const [spaces, setSpaces] = useState<
    Array<{ tenantId: string; tenantName: string; tenantDisplayName: string; spaceType: string }>
  >([]);
  const [actors, setActors] = useState<PartyActorOption[]>([]);

  const userDropdownRef = useRef<HTMLDivElement>(null);

  const workspaceLabel = st('$i18n:header.workspaces', 'Workspaces');
  const platformConsoleLabel = st('$i18n:header.platform_console', 'Platform Console');
  const aboutLabel = t(
    'about.menuLabel',
    { productName: branding.productName },
    locale === 'zh-CN' ? `关于 ${branding.productName}` : `About ${branding.productName}`,
  );

  // Lazy-load spaces for tenant switching in the account menu
  useEffect(() => {
    if (!user || simplified) return;
    fetch('/api/tenant-selection/my-spaces')
      .then((res) => (res.ok ? res.json() : null))
      .then((result) => {
        if (result?.data) setSpaces(result.data);
      })
      .catch(() => {});
  }, [user, simplified]);

  useEffect(() => {
    if (!user || simplified || !rootData?.accessPolicy?.actorSwitchEnabled) return;
    fetch('/api/actors')
      .then((res) => (res.ok ? res.json() : null))
      .then((result) => {
        if (Array.isArray(result?.data)) setActors(result.data);
      })
      .catch(() => {});
  }, [user, simplified, rootData?.accessPolicy?.actorSwitchEnabled]);

  // Close the menu when clicking outside or pressing Escape
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (userDropdownRef.current && !userDropdownRef.current.contains(event.target as Node)) {
        setShowUserDropdown(false);
      }
    };

    const handleEscapeKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setShowUserDropdown(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscapeKey);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscapeKey);
    };
  }, []);

  if (!user) return null;

  return (
    <div
      className="print-hide fixed bottom-4 z-[60] ltr:left-4 rtl:right-4"
      data-print="hide"
      data-testid="user-menu"
      ref={userDropdownRef}
    >
      <button
        onClick={() => setShowUserDropdown(!showUserDropdown)}
        className="flex items-center rounded-full ring-2 ring-transparent transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:shadow-md hover:ring-gray-200 dark:hover:bg-gray-700 dark:hover:ring-gray-600"
      >
        <img
          className="h-[30px] w-[30px] rounded-full border border-[#e3e8ee] object-cover shadow-sm dark:border-gray-700"
          src="/avatar.jpeg"
          alt="User avatar"
        />
      </button>

      {showUserDropdown && (
        <div
          data-testid="user-dropdown"
          className="absolute start-0 bottom-full z-[70] mb-2 w-64 rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-800"
        >
          {/* User info */}
          <div className="border-b border-gray-200 px-4 py-3 dark:border-gray-700">
            <p className="text-sm font-medium text-gray-900 dark:text-white">
              {user.name || t('user.defaultName')}
            </p>
            <p className="truncate text-xs text-gray-500 dark:text-gray-400">{user.email}</p>
          </div>

          {actors.length > 0 && (
            <div className="border-b border-gray-200 py-1 dark:border-gray-700">
              <p className="px-4 py-1 text-xs font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500">
                Business identity
              </p>
              {actors.map((actor) => {
                const selectable =
                  actor.lifecycleStatus === 'active' && actor.membershipStatus === 'active';
                return (
                  <button
                    key={actor.partyMembershipId}
                    type="button"
                    disabled={!selectable || actor.current}
                    data-testid={`actor-switch-${actor.partyId}`}
                    onClick={() => {
                      if (!selectable || actor.current) return;
                      setShowUserDropdown(false);
                      const form = document.createElement('form');
                      form.method = 'POST';
                      form.action = '/_action/switch-actor';
                      const party = document.createElement('input');
                      party.type = 'hidden';
                      party.name = 'partyId';
                      party.value = actor.partyId;
                      form.appendChild(party);
                      const redir = document.createElement('input');
                      redir.type = 'hidden';
                      redir.name = 'redirectTo';
                      redir.value = '/';
                      form.appendChild(redir);
                      document.body.appendChild(form);
                      form.submit();
                    }}
                    className={`flex w-full items-center gap-3 px-4 py-2 text-sm transition-colors ${
                      actor.current
                        ? 'bg-violet-50 font-medium text-violet-700 dark:bg-violet-900/20 dark:text-violet-400'
                        : selectable
                          ? 'text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700'
                          : 'cursor-not-allowed text-gray-400'
                    }`}
                  >
                    <IdentificationIcon className="h-4 w-4 flex-shrink-0" />
                    <span className="truncate">{actor.displayName}</span>
                    {actor.current ? (
                      <span className="ms-auto text-xs">&#10003;</span>
                    ) : !selectable ? (
                      <span className="ms-auto text-xs">{actor.lifecycleStatus}</span>
                    ) : null}
                  </button>
                );
              })}
            </div>
          )}

          {/* Tenant list */}
          {showBusinessWorkspaceSwitcher &&
            spaces.filter((s) => s.spaceType === 'business').length > 0 && (
              <div className="border-b border-gray-200 py-1 dark:border-gray-700">
                <p className="px-4 py-1 text-xs font-semibold tracking-wider text-gray-400 uppercase dark:text-gray-500">
                  {workspaceLabel}
                </p>
                {spaces
                  .filter((s) => s.spaceType === 'business')
                  .map((space) => {
                    const isCurrent = String(user.tenantId) === String(space.tenantId);
                    return (
                      <button
                        key={space.tenantId}
                        data-testid={`tenant-switch-${space.tenantId}`}
                        onClick={() => {
                          if (isCurrent) return;
                          setShowUserDropdown(false);
                          const form = document.createElement('form');
                          form.method = 'POST';
                          form.action = '/_action/switch-space';
                          const tid = document.createElement('input');
                          tid.type = 'hidden';
                          tid.name = 'tenantId';
                          tid.value = space.tenantId;
                          form.appendChild(tid);
                          const redir = document.createElement('input');
                          redir.type = 'hidden';
                          redir.name = 'redirectTo';
                          redir.value = '/';
                          form.appendChild(redir);
                          document.body.appendChild(form);
                          form.submit();
                        }}
                        className={`flex w-full items-center gap-3 px-4 py-2 text-sm transition-colors ${
                          isCurrent
                            ? 'bg-blue-50 font-medium text-blue-700 dark:bg-blue-900/20 dark:text-blue-400'
                            : 'text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700'
                        }`}
                      >
                        <BuildingOffice2Icon
                          className={`h-4 w-4 flex-shrink-0 ${isCurrent ? 'text-blue-500' : 'text-gray-400'}`}
                        />
                        <span className="truncate">
                          {space.tenantDisplayName || space.tenantName}
                        </span>
                        {isCurrent && (
                          <span className="ms-auto text-xs text-blue-500">&#10003;</span>
                        )}
                      </button>
                    );
                  })}
              </div>
            )}

          {/* Platform Console — only for platform_admin users */}
          {spaces.some((s) => s.spaceType === 'platform') && (
            <div className="border-b border-gray-200 py-1 dark:border-gray-700">
              <button
                data-testid="platform-console-link"
                onClick={() => {
                  setShowUserDropdown(false);
                  const platformSpace = spaces.find((s) => s.spaceType === 'platform');
                  if (!platformSpace) return;
                  const form = document.createElement('form');
                  form.method = 'POST';
                  form.action = '/_action/switch-space';
                  const tid = document.createElement('input');
                  tid.type = 'hidden';
                  tid.name = 'tenantId';
                  tid.value = platformSpace.tenantId;
                  form.appendChild(tid);
                  const redir = document.createElement('input');
                  redir.type = 'hidden';
                  redir.name = 'redirectTo';
                  redir.value = '/platform/plugins';
                  form.appendChild(redir);
                  document.body.appendChild(form);
                  form.submit();
                }}
                className="flex w-full items-center gap-3 px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"
              >
                <Cog6ToothIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
                <span>{platformConsoleLabel}</span>
              </button>
            </div>
          )}

          <div className="border-b border-gray-200 py-1 dark:border-gray-700">
            <Link
              to="/about"
              data-testid="about-link"
              className="flex items-center gap-3 px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"
              onClick={() => setShowUserDropdown(false)}
            >
              <InformationCircleIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
              <span>{aboutLabel}</span>
            </Link>
          </div>

          {/* Logout */}
          <Link
            to="/logout"
            className="flex items-center px-4 py-2 text-sm text-red-600 transition-colors hover:bg-gray-100 dark:text-red-400 dark:hover:bg-gray-700"
            onClick={() => setShowUserDropdown(false)}
          >
            <PowerIcon className="me-3 h-4 w-4" />
            {t('user.logout')}
          </Link>
        </div>
      )}
    </div>
  );
}
