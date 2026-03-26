import type { MetaFunction } from 'react-router';

export const meta: MetaFunction = () => [
  { title: 'About — AuraBoot' },
  { name: 'description', content: 'Learn about AuraBoot — our mission to build open-source, AI-powered enterprise software.' },
];

const milestones = [
  { date: 'Feb 2026', title: 'Project Inception', description: 'AuraBoot was born from a simple idea: enterprise software should be powerful yet easy to build.' },
  { date: 'Mar 2026', title: 'Core DSL Engine Complete', description: 'The declarative DSL engine reached production readiness, enabling model-driven development at scale.' },
  { date: 'Mar 2026', title: '27+ Plugins, 2100+ E2E Tests', description: 'A rich plugin ecosystem covering CRM, ERP, BPM, and more — all backed by comprehensive test coverage.' },
  { date: 'Mar 2026', title: 'Open Source Release', description: 'AuraBoot was released to the world, making enterprise-grade low-code accessible to everyone.' },
];

export default function AboutPage() {
  return (
    <div>
      {/* Hero */}
      <section className="relative bg-[#0A0A0A] pt-32 pb-20 overflow-hidden">
        <div className="hero-gradient absolute inset-0" />
        <div className="relative mx-auto max-w-4xl px-6 lg:px-8 text-center">
          <h1 className="text-4xl md:text-6xl font-bold text-white tracking-tight">
            Building the Future of
            <br />
            <span className="text-purple-400">Enterprise Software</span>
          </h1>
          <p className="mt-6 text-lg text-gray-400 max-w-2xl mx-auto">
            Open-source. AI-powered. Enterprise-grade.
          </p>
        </div>
      </section>

      {/* Vision */}
      <section className="py-20">
        <div className="mx-auto max-w-3xl px-6">
          <h2 className="text-3xl font-bold text-gray-900">Our Vision</h2>
          <div className="mt-8 space-y-6 text-lg leading-relaxed text-gray-600">
            <p>
              Enterprise software has been stuck in a frustrating cycle: powerful tools are expensive
              and rigid, while flexible tools lack the depth needed for real business operations.
              AuraBoot was created to break this cycle.
            </p>
            <p>
              We believe the best enterprise software should be declarative, not imperative. By
              combining a DSL-driven architecture with AI-powered tooling and a rich plugin
              ecosystem, AuraBoot lets teams build production-grade business applications in a
              fraction of the time — without sacrificing control or extensibility.
            </p>
            <p>
              Our commitment to open source means that anyone can inspect, modify, and contribute
              to the platform. We are building AuraBoot in the open because we believe transparency
              and community collaboration produce better software for everyone.
            </p>
          </div>
        </div>
      </section>

      {/* Milestones */}
      <section className="py-20 bg-gray-50">
        <div className="mx-auto max-w-3xl px-6">
          <h2 className="text-3xl font-bold text-gray-900 text-center">Milestones</h2>
          <p className="mt-4 text-center text-gray-600">
            Our journey from idea to open-source platform.
          </p>
          <div className="mt-16 relative">
            {/* Vertical line */}
            <div className="absolute left-4 md:left-1/2 md:-translate-x-px top-0 bottom-0 w-0.5 bg-purple-200" />

            <div className="space-y-12">
              {milestones.map((milestone, index) => (
                <div
                  key={index}
                  className={`relative flex items-start gap-8 ${
                    index % 2 === 0 ? 'md:flex-row' : 'md:flex-row-reverse'
                  }`}
                >
                  {/* Dot */}
                  <div className="absolute left-4 md:left-1/2 -translate-x-1/2 w-3 h-3 rounded-full bg-purple-600 ring-4 ring-white mt-1.5" />

                  {/* Content */}
                  <div className={`ml-12 md:ml-0 md:w-1/2 ${index % 2 === 0 ? 'md:pr-12 md:text-right' : 'md:pl-12'}`}>
                    <span className="text-sm font-semibold text-purple-600">{milestone.date}</span>
                    <h3 className="mt-1 text-lg font-bold text-gray-900">{milestone.title}</h3>
                    <p className="mt-2 text-sm text-gray-600">{milestone.description}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* Open Source Commitment */}
      <section className="py-20">
        <div className="mx-auto max-w-3xl px-6 text-center">
          <h2 className="text-3xl font-bold text-gray-900">Open Source Commitment</h2>
          <p className="mt-6 text-lg text-gray-600 leading-relaxed">
            AuraBoot is released under the AuraBoot License, which allows free use for individuals,
            startups, and internal business operations. Our Community edition is and will always
            remain free. We believe that open source is the foundation of trust, and we are
            committed to keeping the core platform accessible to everyone.
          </p>
          <div className="mt-10">
            <a
              href="https://github.com/AuraBootTeam/AuraBoot"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 px-6 py-3 text-sm font-medium text-white bg-gray-900 hover:bg-gray-800 rounded-lg transition-colors"
            >
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path fillRule="evenodd" clipRule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" />
              </svg>
              View on GitHub
            </a>
          </div>
        </div>
      </section>
    </div>
  );
}
