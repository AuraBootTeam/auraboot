import TemplatePointers from '~/framework/pages/custom_components/TemplatePointers';

function LandingIntro() {
  return (
    <div className="hero bg-base-200 min-h-full rounded-l-xl">
      <div className="hero-content py-12">
        <div className="max-w-md">
          <h1 className="text-center text-3xl font-bold">
            <img
              src="/android-chrome-192x192.png"
              className="mask mr-2 inline-block w-12 mask-circle"
              alt="AuraBoot"
            />
            AuraBoot
          </h1>

          <div className="mt-12 text-center">
            <img src="./intro.png" alt="AuraBoot Platform" className="inline-block w-48"></img>
          </div>

          {/* Importing pointers component */}
          <TemplatePointers />
        </div>
      </div>
    </div>
  );
}

export default LandingIntro;
