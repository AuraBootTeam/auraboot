import { useState } from 'react';
import { Section } from './Section';
import { ScrollReveal } from './ScrollReveal';

const TABS = [
  {
    key: 'model',
    label: 'Model',
    heading: 'Define your data model',
    description:
      'Create models with fields, relations, validations, and computed properties. Everything is stored as versionable JSON.',
  },
  {
    key: 'design',
    label: 'Design',
    heading: 'Design pages visually',
    description:
      'Drag and drop forms, tables, charts, and custom blocks. The Page Designer generates DSL pages that render instantly.',
  },
  {
    key: 'run',
    label: 'Run',
    heading: 'Deploy and run',
    description:
      'One-click publish. Your application is live with APIs, permissions, multi-tenant isolation, and audit logging.',
  },
];

export function ProductTour() {
  const [activeTab, setActiveTab] = useState('model');
  const active = TABS.find((t) => t.key === activeTab)!;

  return (
    <Section id="product-tour">
      <div className="text-center mb-12">
        <h2 className="text-3xl md:text-4xl font-bold text-gray-900">See it in action</h2>
      </div>
      <ScrollReveal>
        {/* Tabs */}
        <div className="flex justify-center gap-1 mb-8">
          {TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              onClick={() => setActiveTab(tab.key)}
              className={`px-6 py-3 text-sm font-medium transition-colors border-b-2 ${
                activeTab === tab.key
                  ? 'border-purple-600 text-purple-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Content area */}
        <div className="rounded-xl border border-gray-200 bg-gray-50 p-8 md:p-12">
          <div className="text-center max-w-2xl mx-auto mb-8">
            <h3 className="text-xl font-semibold text-gray-900">{active.heading}</h3>
            <p className="mt-3 text-gray-600">{active.description}</p>
          </div>
          <div className="rounded-lg bg-gray-100 border border-gray-200 h-64 md:h-80 flex items-center justify-center">
            <p className="text-gray-400 text-sm">
              Screenshot placeholder: {active.label} view
            </p>
          </div>
        </div>
      </ScrollReveal>
    </Section>
  );
}
