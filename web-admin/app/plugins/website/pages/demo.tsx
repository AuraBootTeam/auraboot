import type { MetaFunction } from 'react-router';
import { Link } from 'react-router';

export const meta: MetaFunction = () => [
  { title: 'Demo — AuraBoot' },
  { name: 'description', content: 'See AuraBoot in action. Watch the product demo and explore what you can build.' },
];

const features = [
  'Create a business model with fields',
  'Design a page with drag-and-drop',
  'Set up CRUD commands',
  'Configure approval workflows',
  'Install plugins from the marketplace',
];

export default function DemoPage() {
  return (
    <div className="pt-24 pb-20">
      {/* Header */}
      <div className="mx-auto max-w-3xl px-6 text-center">
        <h1 className="text-4xl font-bold tracking-tight text-gray-900 sm:text-5xl">
          See AuraBoot in Action
        </h1>
        <p className="mt-4 text-lg text-gray-600">
          Watch how AuraBoot turns business requirements into working applications in minutes, not months.
        </p>
      </div>

      {/* Video Placeholder */}
      <div className="mx-auto mt-16 max-w-4xl px-6">
        <div className="relative w-full overflow-hidden rounded-xl border border-gray-200 bg-gray-100" style={{ paddingBottom: '56.25%' }}>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <svg className="w-16 h-16 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              <path strokeLinecap="round" strokeLinejoin="round" d="M15.91 11.672a.375.375 0 010 .656l-5.603 3.113a.375.375 0 01-.557-.328V8.887c0-.286.307-.466.557-.327l5.603 3.112z" />
            </svg>
            <p className="mt-4 text-lg font-medium text-gray-500">Product demo video coming soon</p>
          </div>
        </div>
      </div>

      {/* Feature Checklist */}
      <div className="mx-auto mt-20 max-w-2xl px-6">
        <h2 className="text-2xl font-bold text-gray-900 text-center">
          What you can try
        </h2>
        <p className="mt-2 text-center text-gray-600">
          AuraBoot gives you a complete toolkit for building enterprise applications.
        </p>
        <ul className="mt-10 space-y-4">
          {features.map((feature) => (
            <li key={feature} className="flex items-start gap-3">
              <svg className="w-6 h-6 text-green-500 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span className="text-lg text-gray-700">{feature}</span>
            </li>
          ))}
        </ul>
      </div>

      {/* CTA */}
      <div className="mx-auto mt-20 max-w-2xl px-6 text-center">
        <h2 className="text-2xl font-bold text-gray-900">Ready to try?</h2>
        <p className="mt-2 text-gray-600">
          Get started with the free Community edition. No credit card required.
        </p>
        <div className="mt-8">
          <Link
            to="/register"
            className="inline-flex items-center px-8 py-3 text-base font-medium text-white bg-purple-600 hover:bg-purple-700 rounded-lg transition-colors shadow-sm"
          >
            Get Started Free
          </Link>
        </div>
      </div>
    </div>
  );
}
