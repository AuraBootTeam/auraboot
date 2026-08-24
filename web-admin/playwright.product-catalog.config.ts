import base from './playwright.config';

const baseProjects = (base as any).projects ?? [];
const authProject = baseProjects.find((project: any) => project.name === 'auth');
const chromiumProject = baseProjects.find((project: any) => project.name === 'chromium');

if (!authProject || !chromiumProject) {
  throw new Error('Product Catalog verification requires the base auth and chromium projects');
}

export default {
  ...base,
  projects: [
    {
      ...authProject,
      dependencies: [],
    },
    {
      ...chromiumProject,
      dependencies: [],
      testMatch: /prod-catalog-smoke\.spec\.ts$/,
      testIgnore: [],
    },
  ],
};
