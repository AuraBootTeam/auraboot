import type { MetaFunction } from 'react-router';
import { PricingCard } from '../components/PricingCard';
import { PricingTable } from '../components/PricingTable';

export const meta: MetaFunction = () => [
  { title: 'Pricing — AuraBoot' },
  { name: 'description', content: 'AuraBoot pricing: Community (free), Standard, Professional, and Enterprise plans.' },
];

const plans = [
  {
    name: 'Community',
    price: 'Free',
    description: 'Open Source License',
    features: [
      'DSL Engine',
      'Page Designer',
      'BPM Basic',
      'Unlimited users',
      'Community support',
    ],
    ctaText: 'Get Started',
    ctaHref: '/docs/getting-started',
  },
  {
    name: 'Standard',
    price: '$500/year',
    description: 'Commercial License',
    features: [
      'Everything in Community',
      'Automation Rules',
      'Advanced Org Management',
      'Reports & Dashboards',
      'Plugin Marketplace',
      'Email support',
      'Commercial use',
      'Bug-fix updates',
    ],
    ctaText: 'Buy Standard',
    ctaHref: '/contact?plan=standard',
  },
  {
    name: 'Professional',
    price: '$3,000/year',
    description: 'Commercial License',
    highlighted: true,
    features: [
      'Everything in Standard',
      'AI Agent & Bot',
      'ChatBI',
      'Instant Messaging',
      'L1 Plugins (CRM, ERP, etc.)',
      'Multi-tenant support',
      'API rate limit increase',
      'Advanced permissions',
      'Workflow templates',
      'Priority email support',
      'Quarterly roadmap access',
    ],
    ctaText: 'Buy Professional',
    ctaHref: '/contact?plan=professional',
  },
  {
    name: 'Enterprise',
    price: 'Custom',
    description: 'Commercial License',
    features: [
      'Everything in Professional',
      'Industry Solutions',
      'Mobile Apps (iOS & Android)',
      'Priority Support & SLA',
      'Dedicated account manager',
      'Custom integrations',
      'On-premise deployment',
      'SSO & LDAP',
      'Audit logging',
      'White-label branding',
      'Training & onboarding',
      'Source code escrow',
      'Custom SLA',
      'Volume licensing',
    ],
    ctaText: 'Contact Sales',
    ctaHref: '/contact?plan=enterprise',
  },
];

const faqs = [
  {
    question: "What's included in the Community edition?",
    answer:
      'The Community edition is free and open source. It includes the DSL Engine, Page Designer, and BPM Basic — everything you need to build internal tools and manage workflows. You can self-host it with unlimited users at no cost.',
  },
  {
    question: 'Can I upgrade later?',
    answer:
      'Absolutely. You can start with the Community edition and upgrade to any commercial plan at any time. Your data and configurations carry over seamlessly — no migration needed.',
  },
  {
    question: 'Do you offer discounts for startups?',
    answer:
      'Yes. Startups with fewer than 20 employees and under $1M in annual revenue are eligible for 50% off the first year of any commercial plan. Contact us to apply.',
  },
  {
    question: 'What payment methods do you accept?',
    answer:
      'We accept credit cards (Visa, Mastercard, American Express), bank transfers, and PayPal. Enterprise customers can also pay by invoice with Net-30 terms.',
  },
];

export default function PricingPage() {
  return (
    <div className="pt-24 pb-20">
      {/* Header */}
      <div className="mx-auto max-w-3xl px-6 text-center">
        <h1 className="text-4xl font-bold tracking-tight text-gray-900 sm:text-5xl">
          Simple, transparent pricing
        </h1>
        <p className="mt-4 text-lg text-gray-600">
          Start free with the Community edition. Upgrade when you need advanced features, AI capabilities, or enterprise support.
        </p>
      </div>

      {/* Pricing Cards */}
      <div className="mx-auto mt-16 max-w-7xl px-6">
        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {plans.map((plan) => (
            <PricingCard key={plan.name} {...plan} />
          ))}
        </div>
      </div>

      {/* Feature Comparison Table */}
      <div className="mx-auto mt-24 max-w-5xl px-6">
        <h2 className="text-2xl font-bold text-gray-900 text-center">
          Feature comparison
        </h2>
        <p className="mt-2 text-center text-gray-500">
          See exactly what you get with each plan.
        </p>
        <div className="mt-10">
          <PricingTable />
        </div>
      </div>

      {/* FAQ */}
      <div className="mx-auto mt-24 max-w-3xl px-6">
        <h2 className="text-2xl font-bold text-gray-900 text-center">
          Frequently asked questions
        </h2>
        <dl className="mt-10 space-y-4">
          {faqs.map((faq) => (
            <details
              key={faq.question}
              className="group rounded-lg border border-gray-200 bg-white"
            >
              <summary className="flex cursor-pointer items-center justify-between px-5 py-4 text-sm font-medium text-gray-900 select-none">
                {faq.question}
                <svg
                  className="h-5 w-5 shrink-0 text-gray-400 transition-transform group-open:rotate-180"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                </svg>
              </summary>
              <p className="px-5 pb-4 text-sm leading-relaxed text-gray-600">
                {faq.answer}
              </p>
            </details>
          ))}
        </dl>
      </div>
    </div>
  );
}
