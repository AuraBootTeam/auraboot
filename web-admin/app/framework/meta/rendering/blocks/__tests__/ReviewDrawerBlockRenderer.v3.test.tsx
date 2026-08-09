import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { describe, expect, it, vi } from 'vitest';

import { ReviewDrawerBlockRenderer } from '../ReviewDrawerBlockRenderer';

function createRuntime(
  dataSources: Record<string, unknown>,
  selectedRecord: Record<string, unknown>,
) {
  const dataSourceManager = {
    getData: (id: string) => dataSources[id],
    subscribe: vi.fn(() => () => undefined),
    notifyStateChanged: vi.fn(),
  };
  const stateManager = {
    getStore: vi.fn(() => ({ subscribe: vi.fn(() => () => undefined) })),
    updateState: vi.fn(),
  };
  return {
    getContext: () => ({
      locale: 'zh-CN',
      t: (key: string) => key,
      state: { selectedBomLine: selectedRecord },
    }),
    getEvaluator: () => ({ evaluateCondition: () => false }),
    getDataSourceManager: () => dataSourceManager,
    getStateManager: () => stateManager,
    getScopeId: () => 'review-drawer-v3-test',
    getSchema: () => ({ pageKey: 'bom_conversion_task_pcba_workbench' }),
  } as any;
}

describe('ReviewDrawerBlockRenderer V3 contract', () => {
  it('uses category profiles, evidence colors, stacked evidence, and hides redundant decision status', () => {
    const selectedRecord = {
      pid: 'line-2',
      bom_std_raw_row_no: 3,
      bom_std_material_name: '贴片电容',
      bom_std_category: 'capacitor',
    };
    const candidate = {
      pid: 'candidate-1',
      bom_me_material_code: 'D510000914600',
      bom_me_score: 59.33,
      bom_me_candidate_snapshot_json: JSON.stringify({
        category: 'capacitor',
        materialName: '贴片电容',
        specModel: '贴片电容 1nF ±5% 25V 0603 X7R',
        brand: '',
        mpn: '',
        packageCode: '0603',
        attributes: {
          capacitance_farads: '1nF',
          voltage: '25V',
          tolerance_pct: '5%',
          dielectric: 'X7R',
        },
      }),
      bom_me_evidence_json: JSON.stringify({
        groups: {
          brand: {
            comparisons: [{ key: 'brand', status: 'missing_candidate', sourceValue: 'YAGEO' }],
          },
          mpn: {
            comparisons: [{ key: 'mpn', status: 'missing_candidate', sourceValue: 'CC0603' }],
          },
          package: {
            comparisons: [
              { key: 'package', status: 'matched', sourceValue: '0603C', candidateValue: '0603' },
            ],
          },
          parameters: {
            comparisons: [
              { key: 'capacitance', status: 'matched', sourceValue: '1nF', candidateValue: '1nF' },
              { key: 'voltage', status: 'matched', sourceValue: '25V', candidateValue: '25V' },
              { key: 'tolerance', status: 'missing_source', candidateValue: '5%' },
              { key: 'dielectric', status: 'missing_source', candidateValue: 'X7R' },
            ],
          },
        },
      }),
    };
    const runtime = createRuntime(
      {
        standardLines: [selectedRecord],
        rawItems: [
          {
            bom_raw_row_no: 3,
            bom_raw_material_name: '贴片电容',
            bom_raw_extra_columns_json: JSON.stringify({
              __raw_columns: [
                { header: '物料名称', systemField: 'materialName', value: '贴片电容' },
              ],
            }),
          },
        ],
        canonicalLines: [
          {
            pid: 'line-2',
            bom_cl_category: 'capacitor',
            bom_cl_material_name: '贴片电容',
            bom_cl_attributes_json: JSON.stringify({
              capacitance: '1nF',
              voltage: '25V',
              tolerance: '5%',
              dielectric: 'X7R',
              mechanical_position: 'forbidden-for-capacitor',
            }),
          },
        ],
        candidates: [candidate],
      },
      selectedRecord,
    );

    render(
      <ReviewDrawerBlockRenderer
        runtime={runtime}
        block={
          {
            id: 'bom_workbench_review_drawer',
            blockType: 'review-drawer',
            context: '${state.selectedBomLine}',
            contextDataSource: 'standardLines',
            contextKeyField: 'pid',
            layoutMode: 'compact-review',
            compare: {
              layoutMode: 'stacked',
              rawTitle: { 'zh-CN': '原始 BOM 行' },
              rawRecord: {
                dataSource: 'rawItems',
                matchField: 'bom_raw_row_no',
                recordField: 'bom_std_raw_row_no',
              },
              rawFields: [
                { key: 'name', label: { 'zh-CN': '物料名称' }, field: 'bom_raw_material_name' },
              ],
              rawColumns: {
                title: { 'zh-CN': '源 Excel 全列' },
                sourceField: 'bom_raw_extra_columns_json',
                path: '__raw_columns',
              },
              canonicalTitle: { 'zh-CN': '系统标准化结果' },
              canonicalRecord: {
                dataSource: 'canonicalLines',
                matchField: 'pid',
                recordField: 'pid',
              },
              canonicalFields: [
                { key: 'name', label: { 'zh-CN': '物料名称' }, field: 'bom_cl_material_name' },
              ],
              canonicalFieldProfiles: {
                categoryField: 'bom_cl_category',
                profiles: {
                  capacitor: [
                    {
                      key: 'capacitance',
                      label: { 'zh-CN': '容值' },
                      sourceField: 'bom_cl_attributes_json',
                      field: 'capacitance',
                    },
                    {
                      key: 'voltage',
                      label: { 'zh-CN': '耐压' },
                      sourceField: 'bom_cl_attributes_json',
                      field: 'voltage',
                    },
                  ],
                  mechanical: [
                    {
                      key: 'mechanical_position',
                      label: { 'zh-CN': '机械位置' },
                      sourceField: 'bom_cl_attributes_json',
                      field: 'mechanical_position',
                    },
                  ],
                },
              },
            },
            candidates: {
              dataSource: 'candidates',
              layout: 'fieldTable',
              showDecisionStatus: false,
              selectedEvidenceMode: 'summary',
              selectedTitle: { 'zh-CN': '复核摘要' },
              selectedFields: [{ key: 'evidence', field: 'bom_me_evidence_json' }],
              item: {
                titleField: 'bom_me_material_code',
                scoreField: 'bom_me_score',
                fieldColumns: [
                  {
                    key: 'name',
                    label: { 'zh-CN': '物料名称' },
                    sourceField: 'bom_me_candidate_snapshot_json',
                    field: 'materialName',
                  },
                  {
                    key: 'brand',
                    label: { 'zh-CN': '品牌/厂商' },
                    sourceField: 'bom_me_candidate_snapshot_json',
                    field: 'brand',
                    emptyText: '候选未提供',
                    comparisonGroup: 'brand',
                    comparisonKey: 'brand',
                  },
                  {
                    key: 'mpn',
                    label: { 'zh-CN': 'MPN' },
                    sourceField: 'bom_me_candidate_snapshot_json',
                    field: 'mpn',
                    emptyText: '候选未提供',
                    comparisonGroup: 'mpn',
                    comparisonKey: 'mpn',
                  },
                  {
                    key: 'package',
                    label: { 'zh-CN': '封装' },
                    sourceField: 'bom_me_candidate_snapshot_json',
                    field: 'packageCode',
                    comparisonGroup: 'package',
                    comparisonKey: 'package',
                  },
                ],
                fieldProfiles: {
                  categoryField: 'bom_cl_category',
                  candidateCategoryField: 'bom_me_candidate_snapshot_json.category',
                  profiles: {
                    capacitor: [
                      {
                        key: 'capacitance',
                        label: { 'zh-CN': '容值' },
                        sourceField: 'bom_me_candidate_snapshot_json',
                        field: 'attributes.capacitance',
                        fallbackFields: ['attributes.capacitance_farads'],
                        comparisonGroup: 'parameters',
                        comparisonKey: 'capacitance',
                      },
                      {
                        key: 'voltage',
                        label: { 'zh-CN': '耐压' },
                        sourceField: 'bom_me_candidate_snapshot_json',
                        field: 'attributes.voltage',
                        comparisonGroup: 'parameters',
                        comparisonKey: 'voltage',
                      },
                      {
                        key: 'tolerance',
                        label: { 'zh-CN': '误差' },
                        sourceField: 'bom_me_candidate_snapshot_json',
                        field: 'attributes.tolerance_pct',
                        comparisonGroup: 'parameters',
                        comparisonKey: 'tolerance',
                      },
                      {
                        key: 'dielectric',
                        label: { 'zh-CN': '介质' },
                        sourceField: 'bom_me_candidate_snapshot_json',
                        field: 'attributes.dielectric',
                        comparisonGroup: 'parameters',
                        comparisonKey: 'dielectric',
                      },
                    ],
                    mechanical: [
                      {
                        key: 'mechanical_position',
                        label: { 'zh-CN': '机械位置' },
                        sourceField: 'bom_me_candidate_snapshot_json',
                        field: 'attributes.mechanical_position',
                      },
                    ],
                  },
                },
              },
              actions: [{ code: 'confirm_candidate', label: { 'zh-CN': '确认候选' } }],
            },
          } as any
        }
      />,
    );

    expect(screen.getByTestId('review-drawer-content-layout')).toHaveClass(
      'xl:grid-cols-[minmax(320px,0.34fr)_minmax(0,1fr)]',
    );
    expect(screen.getByTestId('review-drawer-tab-compare')).toHaveAttribute(
      'data-layout-mode',
      'stacked',
    );
    const compare = screen.getByTestId('review-drawer-tab-compare');
    const compareSections = Array.from(compare.children);
    expect(compareSections[0]).toHaveTextContent('原始 BOM 行');
    expect(compareSections[1]).toHaveTextContent('系统标准化结果');
    expect(screen.getByTestId('review-drawer-raw-columns')).toHaveTextContent('物料名称');
    expect(screen.getByTestId('review-drawer-selected-group-profile')).toHaveTextContent('容值');
    expect(screen.queryByText('机械位置')).toBeNull();

    const brand = screen.getByTestId('review-drawer-candidate-candidate-1-field-brand');
    expect(brand).toHaveAttribute('data-comparison-status', 'missing_candidate');
    expect(brand).toHaveClass('border-amber-200');
    expect(brand).toHaveTextContent('候选未提供');
    const capacitance = screen.getByTestId('review-drawer-candidate-candidate-1-field-capacitance');
    expect(capacitance).toHaveAttribute('data-comparison-status', 'matched');
    expect(capacitance).toHaveClass('border-emerald-200');
    expect(capacitance).toHaveTextContent('1nF');
    expect(screen.getByTestId('review-drawer-candidate-D510000914600-score')).toHaveTextContent(
      '59.33',
    );
    expect(screen.queryByTestId('review-drawer-decision-status')).toBeNull();
    expect(screen.queryByTestId('review-drawer-decision-panel')).toBeNull();

    fireEvent.click(screen.getByTestId('review-drawer-candidate-candidate-1'));
    expect(screen.getByTestId('review-drawer-evidence-summary')).toHaveTextContent('品牌候选缺失');
    expect(screen.queryByTestId('review-drawer-selected-group-brand')).toBeNull();
    expect(screen.getByTestId('review-drawer-candidate-action-confirm_candidate')).toBeEnabled();
  });
});
