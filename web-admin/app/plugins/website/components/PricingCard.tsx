interface PricingCardProps {
  name: string;
  price: string;
  description: string;
  features: string[];
  highlighted?: boolean;
  ctaText: string;
  ctaHref: string;
}

export function PricingCard({
  name,
  price,
  description,
  features,
  highlighted = false,
  ctaText,
  ctaHref,
}: PricingCardProps) {
  return (
    <div
      className={`relative rounded-xl border p-6 flex flex-col ${
        highlighted
          ? 'border-purple-500 shadow-lg shadow-purple-100'
          : 'border-gray-200'
      }`}
    >
      {highlighted && (
        <span className="absolute -top-3 left-1/2 -translate-x-1/2 rounded-full bg-purple-600 px-3 py-1 text-xs font-medium text-white">
          Most Popular
        </span>
      )}
      <h3 className="text-lg font-semibold text-gray-900">{name}</h3>
      <div className="mt-4">
        <span className="text-3xl font-bold text-gray-900">{price}</span>
      </div>
      <p className="mt-2 text-sm text-gray-500">{description}</p>
      <ul className="mt-6 space-y-3 flex-1">
        {features.map((feature) => (
          <li key={feature} className="flex items-start gap-2 text-sm text-gray-700">
            <svg className="h-5 w-5 flex-shrink-0 text-purple-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
            </svg>
            {feature}
          </li>
        ))}
      </ul>
      <a
        href={ctaHref}
        className={`mt-8 block rounded-lg px-4 py-2.5 text-center text-sm font-medium transition-colors ${
          highlighted
            ? 'bg-purple-600 text-white hover:bg-purple-700'
            : 'border border-gray-300 text-gray-700 hover:border-purple-300 hover:text-purple-600'
        }`}
      >
        {ctaText}
      </a>
    </div>
  );
}
