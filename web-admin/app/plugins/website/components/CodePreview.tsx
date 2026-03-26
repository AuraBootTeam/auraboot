import { Section } from './Section';
import { ScrollReveal } from './ScrollReveal';

const DSL_SNIPPET = `{
  "modelCode": "crm_lead",
  "fields": [
    { "code": "name", "type": "text" },
    { "code": "status", "type": "dict",
      "dictCode": "lead_status" },
    { "code": "score", "type": "integer" }
  ],
  "commands": [
    { "code": "create_lead",
      "type": "create" },
    { "code": "qualify_lead",
      "type": "state_transition" }
  ]
}`;

export function CodePreview() {
  return (
    <Section id="code-preview" className="bg-gray-50">
      <div className="text-center mb-16">
        <h2 className="text-3xl md:text-4xl font-bold text-gray-900">Configuration over code</h2>
        <p className="mt-4 text-lg text-gray-600 max-w-2xl mx-auto">
          Define your entire business application in JSON.
        </p>
      </div>
      <ScrollReveal>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 items-start">
          {/* Code panel */}
          <div className="rounded-xl bg-[#1a1a2e] p-6 overflow-x-auto">
            <div className="flex items-center gap-2 mb-4">
              <span className="h-3 w-3 rounded-full bg-red-500" />
              <span className="h-3 w-3 rounded-full bg-yellow-500" />
              <span className="h-3 w-3 rounded-full bg-green-500" />
              <span className="ml-3 text-xs text-gray-500 font-mono">model.json</span>
            </div>
            <pre className="text-sm leading-relaxed font-mono text-gray-300 whitespace-pre">
              {DSL_SNIPPET}
            </pre>
          </div>

          {/* Result panel */}
          <div className="rounded-xl border border-gray-200 bg-white p-6">
            <h3 className="text-lg font-semibold text-gray-900 mb-4">Auto-generated output</h3>
            <div className="space-y-4">
              <div className="flex items-start gap-3">
                <span className="mt-1 flex h-5 w-5 items-center justify-center rounded-full bg-green-100 text-green-600 text-xs font-bold">1</span>
                <div>
                  <p className="font-medium text-gray-900">Database table</p>
                  <p className="text-sm text-gray-500">PostgreSQL table with indexed columns, tenant isolation</p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <span className="mt-1 flex h-5 w-5 items-center justify-center rounded-full bg-green-100 text-green-600 text-xs font-bold">2</span>
                <div>
                  <p className="font-medium text-gray-900">REST API endpoints</p>
                  <p className="text-sm text-gray-500">CRUD + custom commands with validation and permissions</p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <span className="mt-1 flex h-5 w-5 items-center justify-center rounded-full bg-green-100 text-green-600 text-xs font-bold">3</span>
                <div>
                  <p className="font-medium text-gray-900">UI pages</p>
                  <p className="text-sm text-gray-500">List view, create form, detail page with all fields</p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <span className="mt-1 flex h-5 w-5 items-center justify-center rounded-full bg-green-100 text-green-600 text-xs font-bold">4</span>
                <div>
                  <p className="font-medium text-gray-900">State machine</p>
                  <p className="text-sm text-gray-500">Status transitions with guards, side effects, webhooks</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </ScrollReveal>
    </Section>
  );
}
