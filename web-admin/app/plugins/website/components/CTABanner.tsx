export function CTABanner() {
  return (
    <section className="relative overflow-hidden bg-gradient-to-r from-purple-700 to-indigo-700 py-20 px-6">
      <div className="absolute inset-0 opacity-10">
        <div className="absolute inset-0" style={{
          backgroundImage: 'radial-gradient(circle at 25% 50%, white 1px, transparent 1px)',
          backgroundSize: '40px 40px',
        }} />
      </div>
      <div className="relative mx-auto max-w-4xl text-center">
        <h2 className="text-3xl md:text-4xl font-bold text-white">
          Start building in 5 minutes
        </h2>
        <p className="mt-4 text-lg text-purple-100 max-w-xl mx-auto">
          From idea to production-ready application. No infrastructure setup required.
        </p>
        <div className="mt-8">
          <a
            href="/register"
            className="inline-block rounded-lg bg-white px-8 py-3 text-sm font-semibold text-purple-700 hover:bg-gray-100 transition-colors"
          >
            Get Started Free
          </a>
        </div>
      </div>
    </section>
  );
}
