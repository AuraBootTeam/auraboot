interface SectionProps {
  children: React.ReactNode;
  className?: string;
  id?: string;
  dark?: boolean;
}

export function Section({ children, className = '', id, dark = false }: SectionProps) {
  return (
    <section
      id={id}
      className={`py-20 px-6 ${dark ? 'bg-[#0A0A0A] text-white' : 'bg-white text-gray-900'} ${className}`}
    >
      <div className="mx-auto max-w-7xl">{children}</div>
    </section>
  );
}
