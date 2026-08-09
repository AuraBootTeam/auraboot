import {
  BookOpen,
  ExternalLink,
  Github,
  Globe2,
  Mail,
  Scale,
} from 'lucide-react';
import { useRootLoaderData, type RootLoaderData } from '~/root-data';
import { useI18n } from '~/contexts/I18nContext';
import { COMMUNITY_BRANDING } from '~/config/branding';

export function meta({ matches }: { matches: Array<{ id: string; data?: unknown }> }) {
  const rootData = matches.find((match) => match.id === 'root')?.data as
    | RootLoaderData
    | undefined;
  return [{ title: `About ${rootData?.branding.productName ?? COMMUNITY_BRANDING.productName}` }];
}

function formatEdition(edition: string): string {
  const normalized = edition.trim().toLowerCase();
  if (normalized === 'oss' || normalized === 'community') return 'Community';
  if (normalized === 'enterprise') return 'Enterprise';
  if (normalized === 'professional') return 'Professional';
  if (normalized === 'standard') return 'Standard';
  return edition || 'Community';
}

export default function AboutPage() {
  const rootData = useRootLoaderData();
  const branding = rootData?.branding ?? COMMUNITY_BRANDING;
  const buildIdentity = rootData?.buildIdentity ?? { version: 'development', revision: 'local' };
  const edition = formatEdition(rootData?.edition ?? 'community');
  const { t, locale } = useI18n();
  const isZh = locale.startsWith('zh');
  const currentYear = new Date().getFullYear();

  const labels = {
    eyebrow: t('about.eyebrow', undefined, isZh ? '产品信息' : 'Product information'),
    title: t(
      'about.title',
      { productName: branding.productName },
      isZh ? `关于 ${branding.productName}` : `About ${branding.productName}`,
    ),
    summary: t(
      'about.summary',
      undefined,
      isZh ? '产品身份、构建信息与法律声明。' : 'Product identity, build information, and legal notices.',
    ),
    identity: t('about.identity', undefined, isZh ? '实例信息' : 'Instance information'),
    product: t('about.product', undefined, isZh ? '产品' : 'Product'),
    edition: t('about.edition', undefined, isZh ? '版本' : 'Edition'),
    version: t('about.version', undefined, isZh ? '构建版本' : 'Build version'),
    revision: t('about.revision', undefined, isZh ? '构建修订' : 'Build revision'),
    license: t('about.license', undefined, isZh ? '许可证' : 'License'),
    resources: t('about.resources', undefined, isZh ? '产品与法律资源' : 'Product and legal resources'),
    website: t('about.website', undefined, isZh ? '官方网站' : 'Website'),
    docs: t('about.docs', undefined, isZh ? '产品文档' : 'Documentation'),
    support: t('about.support', undefined, isZh ? '客户支持' : 'Customer support'),
    source: t('about.source', undefined, isZh ? '源代码' : 'Source code'),
    contact: t('about.contact', undefined, isZh ? '商业授权与支持' : 'Commercial licensing and support'),
    legal: t('about.legal', undefined, isZh ? '法律与品牌' : 'Legal and branding'),
    legalNote: t(
      'about.legalNote',
      undefined,
      branding.mode === 'commercial'
        ? isZh
          ? '本部署依据商业订单使用客户品牌。AuraBoot 源码版权、License 和第三方 notices 仍然适用。'
          : 'This deployment uses customer branding under a commercial order. AuraBoot source copyright, License, and third-party notices remain applicable.'
        : isZh
          ? 'Community 部署必须保留 AuraBoot 品牌与相关 notices。白标、移除品牌或官方支持需要单独的 Commercial License。'
          : 'Community deployments must retain AuraBoot branding and required notices. White-labeling, brand removal, and official support require a separate Commercial License.',
    ),
  };

  const resources = [
    { label: labels.website, href: branding.websiteUrl, icon: Globe2 },
    { label: labels.docs, href: branding.docsUrl, icon: BookOpen },
    {
      label: branding.mode === 'commercial' ? labels.support : labels.contact,
      href: branding.mode === 'commercial' ? branding.supportUrl : branding.commercialContactUrl,
      icon: Mail,
    },
    { label: labels.source, href: branding.sourceUrl, icon: Github },
  ];

  return (
    <div
      data-testid="about-page"
      data-branding-mode={branding.mode}
      className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8 lg:py-12"
    >
      <header className="flex flex-col gap-5 border-b border-gray-200 pb-8 sm:flex-row sm:items-center dark:border-gray-700">
        <img
          src={branding.logoUrl}
          alt={branding.productName}
          className="h-16 w-16 rounded-lg shadow-sm"
        />
        <div className="min-w-0">
          <p className="text-sm font-medium text-blue-600 dark:text-blue-400">{labels.eyebrow}</p>
          <div className="mt-1 flex flex-wrap items-center gap-3">
            <h1 className="text-3xl font-semibold text-gray-950 dark:text-white">{labels.title}</h1>
            <span
              data-testid="about-edition"
              className="rounded-full border border-gray-300 bg-white px-2.5 py-1 text-xs font-medium text-gray-600 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300"
            >
              {edition}
            </span>
          </div>
          <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">{labels.summary}</p>
        </div>
      </header>

      <div className="grid gap-10 py-8 lg:grid-cols-[minmax(0,1.4fr)_minmax(260px,0.8fr)]">
        <section aria-labelledby="about-instance-heading">
          <h2
            id="about-instance-heading"
            className="text-base font-semibold text-gray-950 dark:text-white"
          >
            {labels.identity}
          </h2>
          <dl className="mt-4 divide-y divide-gray-200 border-y border-gray-200 dark:divide-gray-700 dark:border-gray-700">
            {[
              [labels.product, branding.platformName],
              [labels.edition, edition],
              [labels.version, buildIdentity.version],
              [labels.revision, buildIdentity.revision],
            ].map(([label, value]) => (
              <div key={label} className="grid grid-cols-[minmax(110px,0.45fr)_minmax(0,1fr)] gap-4 py-3.5 text-sm">
                <dt className="text-gray-500 dark:text-gray-400">{label}</dt>
                <dd className="break-words font-medium text-gray-900 dark:text-gray-100">{value}</dd>
              </div>
            ))}
            <div className="grid grid-cols-[minmax(110px,0.45fr)_minmax(0,1fr)] gap-4 py-3.5 text-sm">
              <dt className="text-gray-500 dark:text-gray-400">{labels.license}</dt>
              <dd>
                <a
                  href={branding.licenseUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1.5 font-medium text-blue-600 hover:text-blue-700 dark:text-blue-400 dark:hover:text-blue-300"
                >
                  <Scale className="h-4 w-4" aria-hidden="true" />
                  {branding.licenseName}
                  <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                </a>
              </dd>
            </div>
          </dl>
        </section>

        <section aria-labelledby="about-resources-heading">
          <h2
            id="about-resources-heading"
            className="text-base font-semibold text-gray-950 dark:text-white"
          >
            {labels.resources}
          </h2>
          <nav className="mt-4 divide-y divide-gray-200 border-y border-gray-200 dark:divide-gray-700 dark:border-gray-700">
            {resources.map(({ label, href, icon: Icon }) => (
              <a
                key={href}
                href={href}
                target="_blank"
                rel="noreferrer"
                className="flex min-h-12 items-center gap-3 py-3 text-sm font-medium text-gray-700 transition-colors hover:text-blue-600 dark:text-gray-300 dark:hover:text-blue-400"
              >
                <Icon className="h-4 w-4 text-gray-400" aria-hidden="true" />
                <span className="min-w-0 flex-1">{label}</span>
                <ExternalLink className="h-3.5 w-3.5 text-gray-400" aria-hidden="true" />
              </a>
            ))}
          </nav>
        </section>
      </div>

      <section aria-labelledby="about-legal-heading" className="border-t border-gray-200 pt-7 dark:border-gray-700">
        <h2 id="about-legal-heading" className="text-base font-semibold text-gray-950 dark:text-white">
          {labels.legal}
        </h2>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-gray-600 dark:text-gray-400">
          {labels.legalNote}
        </p>
        <p data-testid="about-owner-copyright" className="mt-4 text-xs text-gray-500 dark:text-gray-500">
          © {currentYear} {branding.copyrightHolder}
        </p>
        {branding.mode === 'commercial' && (
          <p data-testid="about-auraboot-notice" className="mt-1 text-xs text-gray-500 dark:text-gray-500">
            © {currentYear} {branding.aurabootCopyrightHolder} · {branding.licenseName}
          </p>
        )}
      </section>
    </div>
  );
}
