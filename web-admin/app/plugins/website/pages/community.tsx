import type { MetaFunction } from 'react-router';
import { Link } from 'react-router';

export const meta: MetaFunction = () => [
  { title: 'Community — AuraBoot' },
  { name: 'description', content: 'Join the AuraBoot community. Contribute on GitHub, chat on Discord, and help shape the future of enterprise low-code.' },
];

const channels = [
  {
    title: 'GitHub',
    description: 'Star us, fork us, contribute. Browse the source code, report issues, and submit pull requests.',
    href: 'https://github.com/AuraBootTeam/AuraBoot',
    external: true,
    icon: (
      <svg className="w-8 h-8" fill="currentColor" viewBox="0 0 24 24">
        <path fillRule="evenodd" clipRule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" />
      </svg>
    ),
  },
  {
    title: 'Discord',
    description: 'Join the community chat. Get help, share what you are building, and connect with other developers.',
    href: '#',
    external: false,
    badge: 'Coming soon',
    icon: (
      <svg className="w-8 h-8" fill="currentColor" viewBox="0 0 24 24">
        <path d="M20.317 4.37a19.791 19.791 0 00-4.885-1.515.074.074 0 00-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 00-5.487 0 12.64 12.64 0 00-.617-1.25.077.077 0 00-.079-.037A19.736 19.736 0 003.677 4.37a.07.07 0 00-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 00.031.057 19.9 19.9 0 005.993 3.03.078.078 0 00.084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 00-.041-.106 13.107 13.107 0 01-1.872-.892.077.077 0 01-.008-.128 10.2 10.2 0 00.372-.292.074.074 0 01.077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 01.078.01c.12.098.246.198.373.292a.077.077 0 01-.006.127 12.299 12.299 0 01-1.873.892.077.077 0 00-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 00.084.028 19.839 19.839 0 006.002-3.03.077.077 0 00.032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 00-.031-.03z" />
      </svg>
    ),
  },
  {
    title: 'Discussions',
    description: 'Ask questions, share ideas, and participate in feature discussions. Your feedback shapes the roadmap.',
    href: 'https://github.com/AuraBootTeam/AuraBoot/discussions',
    external: true,
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M20.25 8.511c.884.284 1.5 1.128 1.5 2.097v4.286c0 1.136-.847 2.1-1.98 2.193-.34.027-.68.052-1.02.072v3.091l-3-3c-1.354 0-2.694-.055-4.02-.163a2.115 2.115 0 01-.825-.242m9.345-8.334a2.126 2.126 0 00-.476-.095 48.64 48.64 0 00-8.048 0c-1.131.094-1.976 1.057-1.976 2.192v4.286c0 .837.46 1.58 1.155 1.951m9.345-8.334V6.637c0-1.621-1.152-3.026-2.76-3.235A48.455 48.455 0 0011.25 3c-2.115 0-4.198.137-6.24.402-1.608.209-2.76 1.614-2.76 3.235v6.226c0 1.621 1.152 3.026 2.76 3.235.577.075 1.157.14 1.74.194V21l4.155-4.155" />
      </svg>
    ),
  },
  {
    title: 'Contributing',
    description: 'Read our contribution guide. Learn how to set up the development environment and submit your first PR.',
    href: '/docs/guides/plugin-development',
    external: false,
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M17.25 6.75L22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3l-4.5 16.5" />
      </svg>
    ),
  },
  {
    title: 'Blog',
    description: 'Latest news, tutorials, and deep dives into AuraBoot features and architecture.',
    href: '/blog',
    external: false,
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 7.5h1.5m-1.5 3h1.5m-7.5 3h7.5m-7.5 3h7.5m3-9h3.375c.621 0 1.125.504 1.125 1.125V18a2.25 2.25 0 01-2.25 2.25M16.5 7.5V18a2.25 2.25 0 002.25 2.25M16.5 7.5V4.875c0-.621-.504-1.125-1.125-1.125H4.125C3.504 3.75 3 4.254 3 4.875V18a2.25 2.25 0 002.25 2.25h13.5" />
      </svg>
    ),
  },
  {
    title: 'Security',
    description: 'Report vulnerabilities responsibly. We take security seriously and respond promptly to all reports.',
    href: '/SECURITY.md',
    external: false,
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
      </svg>
    ),
  },
];

export default function CommunityPage() {
  return (
    <div className="pt-24 pb-20">
      {/* Header */}
      <div className="mx-auto max-w-3xl px-6 text-center">
        <h1 className="text-4xl font-bold tracking-tight text-gray-900 sm:text-5xl">
          Community
        </h1>
        <p className="mt-4 text-lg text-gray-600">
          AuraBoot is built by and for the community. Join us, contribute, and help shape the future of enterprise software.
        </p>
      </div>

      {/* Channel Grid */}
      <div className="mx-auto mt-16 max-w-5xl px-6">
        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {channels.map((channel) => {
            const cardContent = (
              <div className="relative flex flex-col h-full rounded-xl border border-gray-200 bg-white p-6 shadow-sm hover:shadow-md hover:border-purple-300 transition-all">
                {channel.badge && (
                  <span className="absolute top-4 right-4 inline-flex items-center rounded-full bg-purple-100 px-2.5 py-0.5 text-xs font-medium text-purple-700">
                    {channel.badge}
                  </span>
                )}
                <div className="text-purple-600">{channel.icon}</div>
                <h3 className="mt-4 text-lg font-semibold text-gray-900">{channel.title}</h3>
                <p className="mt-2 text-sm text-gray-600 flex-1">{channel.description}</p>
                <div className="mt-4 text-sm font-medium text-purple-600 flex items-center gap-1">
                  {channel.external ? 'Visit' : 'Learn more'}
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M13.5 4.5L21 12m0 0l-7.5 7.5M21 12H3" />
                  </svg>
                </div>
              </div>
            );

            if (channel.external) {
              return (
                <a
                  key={channel.title}
                  href={channel.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="block"
                >
                  {cardContent}
                </a>
              );
            }

            return (
              <Link key={channel.title} to={channel.href} className="block">
                {cardContent}
              </Link>
            );
          })}
        </div>
      </div>
    </div>
  );
}
