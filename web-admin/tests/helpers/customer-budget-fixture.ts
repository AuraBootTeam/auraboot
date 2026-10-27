/** Independent fictional customer shared by live analysis and print-layout journeys. */
export const customerBudgetFixture = {
  customerName: '景川精密制造',
  projects: [
    { name: '供应链协同平台', budget: 128000, code: 'JC-2026-001' },
    { name: '服务交付二期', budget: 286000, code: 'JC-2026-002' },
    { name: '供应商对账升级', budget: 132000.5, code: 'JC-2026-003' },
  ],
} as const;
