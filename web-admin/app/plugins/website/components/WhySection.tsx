import { Section } from './Section';
import { ScrollReveal } from './ScrollReveal';

const PILLARS = [
  {
    icon: (
      <svg className="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
      </svg>
    ),
    title: 'DSL as Code',
    description:
      'Your business logic lives in version-controlled JSON, not in a proprietary database. Full Git workflow, diff, review.',
  },
  {
    icon: (
      <svg className="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
      </svg>
    ),
    title: 'Plugin Marketplace',
    description:
      '27+ plugins across industry layers. Build once, reuse across projects. Install with one click.',
  },
  {
    icon: (
      <svg className="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M13 10V3L4 14h7v7l9-11h-7z" />
      </svg>
    ),
    title: 'AI Agent Runtime',
    description:
      '8 LLM providers, Agent Control Plane, autonomous task execution. AI-native from day one.',
  },
  {
    icon: (
      <svg className="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197m13.5-9a2.5 2.5 0 11-5 0 2.5 2.5 0 015 0z" />
      </svg>
    ),
    title: 'Multi-Tenant SaaS',
    description:
      'Built-in tenant isolation, RBAC, data permissions. Deploy once, serve many organizations.',
  },
];

export function WhySection() {
  return (
    <Section id="why" className="bg-gray-50">
      <div className="text-center mb-16">
        <h2 className="text-3xl md:text-4xl font-bold text-gray-900">Why AuraBoot?</h2>
        <p className="mt-4 text-lg text-gray-600 max-w-2xl mx-auto">
          Not just another low-code platform.
        </p>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {PILLARS.map((pillar) => (
          <ScrollReveal key={pillar.title}>
            <div className="flex gap-5">
              <div className="flex-shrink-0 text-purple-600">{pillar.icon}</div>
              <div>
                <h3 className="text-xl font-semibold text-gray-900">{pillar.title}</h3>
                <p className="mt-2 text-gray-600 leading-relaxed">{pillar.description}</p>
              </div>
            </div>
          </ScrollReveal>
        ))}
      </div>
    </Section>
  );
}
