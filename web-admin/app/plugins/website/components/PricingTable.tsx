const features = [
  { name: 'DSL Engine', community: true, standard: true, professional: true, enterprise: true },
  { name: 'Page Designer', community: true, standard: true, professional: true, enterprise: true },
  { name: 'BPM Basic', community: true, standard: true, professional: true, enterprise: true },
  { name: 'Automation Rules', community: false, standard: true, professional: true, enterprise: true },
  { name: 'Advanced Org', community: false, standard: true, professional: true, enterprise: true },
  { name: 'Reports', community: false, standard: true, professional: true, enterprise: true },
  { name: 'AI (Agent / Bot / ChatBI)', community: false, standard: false, professional: true, enterprise: true },
  { name: 'IM', community: false, standard: false, professional: true, enterprise: true },
  { name: 'L1 Plugins (CRM / ERP)', community: false, standard: false, professional: true, enterprise: true },
  { name: 'Industry Solutions', community: false, standard: false, professional: false, enterprise: true },
  { name: 'Mobile Apps', community: false, standard: false, professional: false, enterprise: true },
  { name: 'Priority Support', community: false, standard: false, professional: false, enterprise: true },
];

const tiers = ['Community', 'Standard', 'Professional', 'Enterprise'] as const;

function Check() {
  return (
    <svg className="mx-auto h-5 w-5 text-purple-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
    </svg>
  );
}

function Dash() {
  return <span className="block text-center text-gray-300">&mdash;</span>;
}

export function PricingTable() {
  return (
    <div className="overflow-x-auto -mx-6 px-6">
      <table className="w-full min-w-[600px] border-collapse text-sm">
        <thead>
          <tr className="border-b border-gray-200">
            <th className="sticky left-0 z-10 bg-white py-4 pr-4 text-left font-semibold text-gray-900">
              Feature
            </th>
            {tiers.map((tier) => (
              <th
                key={tier}
                className={`py-4 px-4 text-center font-semibold ${
                  tier === 'Professional'
                    ? 'text-purple-600 bg-purple-50/60'
                    : 'text-gray-900'
                }`}
              >
                {tier}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {features.map((feature, idx) => (
            <tr
              key={feature.name}
              className={idx % 2 === 0 ? 'bg-gray-50/50' : 'bg-white'}
            >
              <td className="sticky left-0 z-10 bg-inherit py-3 pr-4 font-medium text-gray-700">
                {feature.name}
              </td>
              <td className="py-3 px-4">{feature.community ? <Check /> : <Dash />}</td>
              <td className="py-3 px-4">{feature.standard ? <Check /> : <Dash />}</td>
              <td className={`py-3 px-4 ${feature.professional ? 'bg-purple-50/60' : 'bg-purple-50/30'}`}>
                {feature.professional ? <Check /> : <Dash />}
              </td>
              <td className="py-3 px-4">{feature.enterprise ? <Check /> : <Dash />}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
