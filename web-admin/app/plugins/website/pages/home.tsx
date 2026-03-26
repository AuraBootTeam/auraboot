import type { MetaFunction } from 'react-router';
import { Link } from 'react-router';
import { Section } from '../components/Section';
import { WhySection } from '../components/WhySection';
import { FeatureGrid } from '../components/FeatureGrid';
import { ProductTour } from '../components/ProductTour';
import { CodePreview } from '../components/CodePreview';
import { StatsBar } from '../components/StatsBar';
import { PricingCard } from '../components/PricingCard';
import { CTABanner } from '../components/CTABanner';

export const meta: MetaFunction = () => [
  { title: 'AuraBoot — AI-Powered Enterprise Low-Code Platform' },
  { name: 'description', content: 'Build enterprise apps 10x faster with DSL-driven development, visual page designer, and AI copilot. 27+ plugins, 2100+ tests, production-ready.' },
  { property: 'og:title', content: 'AuraBoot — Build Enterprise Apps 10x Faster' },
  { property: 'og:description', content: 'AI-powered, DSL-driven, plugin-extensible low-code platform.' },
  { property: 'og:type', content: 'website' },
];

const PRICING_TIERS = [
  {
    name: 'Community',
    price: 'Free',
    description: 'For individuals and small teams getting started.',
    features: [
      '3 users included',
      'Core DSL engine',
      'Page Designer',
      'Community plugins',
      'Community support',
    ],
    ctaText: 'Get Started',
    ctaHref: '/register',
  },
  {
    name: 'Standard',
    price: '$500/yr',
    description: 'For growing teams that need more power.',
    features: [
      '10 users included',
      'All Community features',
      'BPM Workflow engine',
      'Custom commands',
      'Email support',
      'Plugin marketplace access',
      'Data export',
      'API access',
    ],
    ctaText: 'Start Free Trial',
    ctaHref: '/register?plan=standard',
  },
  {
    name: 'Professional',
    price: '$3,000/yr',
    description: 'For teams building mission-critical applications.',
    highlighted: true,
    features: [
      'Unlimited users',
      'All Standard features',
      'AI Copilot (AuraBot)',
      'Agent Control Plane',
      'Multi-tenant SaaS mode',
      'Advanced permissions',
      'Audit logging',
      'Priority support',
      'Custom integrations',
      'SSO / SAML',
      'SLA guarantee',
    ],
    ctaText: 'Start Free Trial',
    ctaHref: '/register?plan=professional',
  },
  {
    name: 'Enterprise',
    price: 'Custom',
    description: 'For large organizations with complex requirements.',
    features: [
      'Unlimited everything',
      'All Professional features',
      'On-premise deployment',
      'Dedicated support engineer',
      'Custom SLA',
      'Source code license',
      'Training & onboarding',
      'Security review',
      'Compliance certification',
      'Custom plugin development',
      'Architecture consulting',
      'Multi-region deployment',
      'Data residency options',
      'Executive business reviews',
    ],
    ctaText: 'Contact Sales',
    ctaHref: '/contact',
  },
];

export default function HomePage() {
  return (
    <div>
      {/* Hero */}
      <section className="relative bg-[#0A0A0A] overflow-hidden">
        <div className="hero-gradient absolute inset-0" />
        <div className="relative mx-auto max-w-7xl px-6 lg:px-8 text-center pt-28 pb-20 md:pt-36 md:pb-28">
          <h1 className="text-5xl md:text-7xl font-bold text-white tracking-tight leading-tight">
            Build Enterprise Apps
            <br />
            <span className="text-purple-400">10x Faster</span>
          </h1>
          <p className="mt-6 text-lg md:text-xl lg:text-2xl text-gray-400 max-w-2xl mx-auto">
            AI-powered, DSL-driven, plugin-extensible low-code platform
            for building production-grade business applications.
          </p>
          <div className="mt-10 flex items-center justify-center gap-4 flex-wrap">
            <a href="/register" className="px-8 py-3.5 text-sm font-medium text-white bg-purple-600 hover:bg-purple-700 rounded-lg transition-colors">
              Get Started Free
            </a>
            <a href="/demo" className="px-8 py-3.5 text-sm font-medium text-gray-300 border border-gray-700 hover:border-gray-500 rounded-lg transition-colors">
              Live Demo
            </a>
          </div>
        </div>
      </section>

      {/* Why AuraBoot */}
      <WhySection />

      {/* Feature Grid */}
      <FeatureGrid />

      {/* Product Tour */}
      <ProductTour />

      {/* Code Preview */}
      <CodePreview />

      {/* Stats Bar */}
      <StatsBar />

      {/* Pricing */}
      <Section id="pricing">
        <div className="text-center mb-16">
          <h2 className="text-3xl md:text-4xl font-bold text-gray-900">
            Simple, transparent pricing
          </h2>
          <p className="mt-4 text-lg text-gray-600 max-w-2xl mx-auto">
            Start free. Scale as you grow. No hidden fees.
          </p>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {PRICING_TIERS.map((tier) => (
            <PricingCard key={tier.name} {...tier} />
          ))}
        </div>
        <div className="mt-8 text-center">
          <Link to="/pricing" className="text-sm font-medium text-purple-600 hover:text-purple-700 transition-colors">
            View full pricing &rarr;
          </Link>
        </div>
      </Section>

      {/* CTA Banner */}
      <CTABanner />
    </div>
  );
}
