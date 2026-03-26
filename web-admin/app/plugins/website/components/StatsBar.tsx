import { useRef, useEffect, useState } from 'react';
import { Section } from './Section';

interface StatItemProps {
  value: number;
  suffix: string;
  label: string;
  triggered: boolean;
}

function StatItem({ value, suffix, label, triggered }: StatItemProps) {
  const [count, setCount] = useState(0);

  useEffect(() => {
    if (!triggered) return;
    const duration = 1500;
    const steps = 40;
    const increment = value / steps;
    let current = 0;
    const timer = setInterval(() => {
      current += increment;
      if (current >= value) {
        setCount(value);
        clearInterval(timer);
      } else {
        setCount(Math.floor(current));
      }
    }, duration / steps);
    return () => clearInterval(timer);
  }, [triggered, value]);

  return (
    <div className="text-center">
      <div className="text-3xl md:text-4xl lg:text-5xl font-bold text-white whitespace-nowrap" style={{ fontVariantNumeric: 'tabular-nums' }}>
        {count}
        <span className="text-purple-400">{suffix}</span>
      </div>
      <div className="mt-2 text-sm text-gray-400">{label}</div>
    </div>
  );
}

const STATS = [
  { value: 27, suffix: '+', label: 'Plugins' },
  { value: 2100, suffix: '+', label: 'E2E Tests' },
  { value: 20, suffix: '', label: 'Pipeline Stages' },
  { value: 126, suffix: '+', label: 'ERP Models' },
];

export function StatsBar() {
  const ref = useRef<HTMLDivElement>(null);
  const [triggered, setTriggered] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setTriggered(true);
          observer.unobserve(el);
        }
      },
      { threshold: 0.3 }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return (
    <Section dark>
      <div ref={ref} className="grid grid-cols-2 lg:grid-cols-4 gap-8 md:gap-12">
        {STATS.map((stat) => (
          <StatItem key={stat.label} {...stat} triggered={triggered} />
        ))}
      </div>
    </Section>
  );
}
