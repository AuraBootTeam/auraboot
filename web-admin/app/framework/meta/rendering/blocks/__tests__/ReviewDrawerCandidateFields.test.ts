import { describe, expect, it } from 'vitest';

import {
  resolveCandidateFieldColumns,
  resolveProfiledFieldGroups,
  resolveProfiledFieldColumns,
} from '../ReviewDrawerCandidateFields';

const baseFields = [
  { key: 'code', field: 'bom_me_material_code' },
  { key: 'score', field: 'bom_me_score' },
  { key: 'name', sourceField: 'bom_me_candidate_snapshot_json', field: 'materialName' },
  { key: 'spec', sourceField: 'bom_me_candidate_snapshot_json', field: 'specModel' },
  { key: 'package', sourceField: 'bom_me_candidate_snapshot_json', field: 'packageCode' },
  {
    key: 'tolerance',
    sourceField: 'bom_me_candidate_snapshot_json',
    field: 'attributes.tolerance_pct',
  },
];

describe('resolveCandidateFieldColumns', () => {
  const item = {
    fieldColumns: baseFields,
    fieldProfiles: {
      categoryField: 'bom_cl_category',
      candidateCategoryField: 'bom_me_candidate_snapshot_json.category',
      attributeSourceField: 'bom_me_candidate_snapshot_json.attributes',
      profiles: {
        resistor: [
          {
            key: 'resistance',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.resistance',
          },
          {
            key: 'tolerance',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.tolerance_pct',
          },
        ],
        capacitor: [
          {
            key: 'capacitance',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.capacitance',
          },
          {
            key: 'voltage',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.voltage',
          },
          {
            key: 'tolerance',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.tolerance_pct',
          },
          {
            key: 'dielectric',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.dielectric',
          },
        ],
        model_based: [
          {
            key: 'model',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.model',
            fallbackFields: ['attributes.model_text', 'mpn'],
          },
        ],
        diode: [
          {
            key: 'model',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.model',
            fallbackFields: ['attributes.model_text', 'mpn'],
          },
          {
            key: 'reverse_voltage',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.reverse_voltage',
            fallbackFields: [
              'attributes.reverse_voltage_volts',
              'attributes.voltage',
              'attributes.voltage_volts',
            ],
          },
          {
            key: 'current',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.current',
            fallbackFields: ['attributes.current_amps'],
          },
        ],
        led: [
          {
            key: 'color',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.color',
          },
          {
            key: 'wavelength',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.wavelength',
            fallbackFields: ['attributes.wavelength_nm'],
          },
          {
            key: 'current',
            sourceField: 'bom_me_candidate_snapshot_json',
            field: 'attributes.current',
            fallbackFields: ['attributes.current_amps'],
          },
        ],
      },
      aliases: {
        diode: 'diode',
      },
      otherField: {
        key: 'other',
        label: { 'zh-CN': '其他', en: 'Other' },
      },
    },
  };

  it('uses the standardized resistor category to show resistor fields and collapse non-profile attributes into Other', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'D410000050700',
        bom_me_score: 65.8,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'capacitor',
          materialName: '贴片电阻',
          specModel: '贴片电阻 33Ω ±1% 0402',
          packageCode: '0402',
          attributes: {
            resistance: '33Ω',
            tolerance_pct: 0.01,
            temperature_range: '-55℃~+155℃',
            capacitance: '10uF',
            power: '',
            temperature_coefficient: '',
            package: '0402',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'resistor',
      },
    });

    expect(fields.map((field) => field.key)).toEqual([
      'code',
      'score',
      'name',
      'spec',
      'package',
      'resistance',
      'tolerance',
      'other',
    ]);
    expect(fields.find((field) => field.key === 'capacitance')).toBeUndefined();
    expect(fields.find((field) => field.key === 'voltage')).toBeUndefined();
    expect(fields.find((field) => field.key === 'dielectric')).toBeUndefined();
    expect(fields.find((field) => field.key === 'power')).toBeUndefined();
    expect(fields.find((field) => field.key === 'temperature_coefficient')).toBeUndefined();
    expect(fields.find((field) => field.key === 'other')?.value).toBe(
      'temperature_range: -55℃~+155℃；capacitance: 10uF',
    );
  });

  it('uses the capacitor category to show capacitor fields and keep resistor-only values in Other', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'D510000026700',
        bom_me_score: 63.78,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'capacitor',
          materialName: '贴片电容',
          specModel: '贴片电容 100nF ±10% 50V X7R 0201',
          packageCode: '0201',
          attributes: {
            capacitance: '100nF',
            voltage: '50V',
            dielectric: 'X7R',
            resistance: '33Ω',
            temperature_range: '-55℃~+125℃',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'capacitor',
      },
    });

    expect(fields.map((field) => field.key)).toEqual([
      'code',
      'score',
      'name',
      'spec',
      'package',
      'capacitance',
      'voltage',
      'tolerance',
      'dielectric',
      'other',
    ]);
    expect(fields.find((field) => field.key === 'resistance')).toBeUndefined();
    expect(fields.find((field) => field.key === 'other')?.value).toBe(
      'resistance: 33Ω；temperature_range: -55℃~+125℃',
    );
  });

  it('falls back to common fields and Other when the category has no profile', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'C020000000100',
        bom_me_score: 61.5,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'connector',
          materialName: '连接器',
          specModel: '2.54mm 2P 直插连接器',
          packageCode: 'DIP-2P',
          attributes: {
            pitch: '2.54mm',
            pins: '2P',
            gender: 'female',
            voltage: '250V',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'connector',
      },
    });

    expect(fields.map((field) => field.key)).toEqual([
      'code',
      'score',
      'name',
      'spec',
      'package',
      'other',
    ]);
    expect(fields.find((field) => field.key === 'capacitance')).toBeUndefined();
    expect(fields.find((field) => field.key === 'resistance')).toBeUndefined();
    expect(fields.find((field) => field.key === 'other')?.value).toBe(
      'pitch: 2.54mm；pins: 2P；gender: female；voltage: 250V',
    );
  });

  it('uses model_text as the model fallback without leaking it into Other', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'D310000011200',
        bom_me_score: 62.6,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'model_based',
          materialName: '贴片二极管',
          specModel: '贴片ESD二极管 BTR04A02 0402',
          packageCode: '0402',
          mpn: '',
          attributes: {
            package: '0402',
            model_text: 'BTR04A02',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'model_based',
      },
    });

    expect(fields.map((field) => field.key)).toEqual([
      'code',
      'score',
      'name',
      'spec',
      'package',
      'model',
    ]);
    expect(fields.find((field) => field.key === 'other')).toBeUndefined();
  });

  it('does not leak camelCase modelText into Other', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'D910000002000',
        bom_me_score: 69.15,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'switch',
          materialName: '贴片轻触开关',
          specModel: '贴片轻触开关 1185A140',
          packageCode: '',
          attributes: {
            modelText: '1185A140',
            package: '',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'switch',
      },
    });

    expect(fields.find((field) => field.key === 'other')).toBeUndefined();
  });

  it('filters internal model and package aliases from Other while keeping user-facing leftovers', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'D310000011200',
        bom_me_score: 62.6,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'diode',
          materialName: '贴片二极管',
          specModel: '贴片ESD二极管 BTR04A02 0402',
          packageCode: '0402',
          attributes: {
            modelValue: 'BTR04A02',
            normalized_package: '0402',
            package: '0402',
            clamp_voltage: '5V',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'diode',
      },
    });

    expect(fields.find((field) => field.key === 'other')?.value).toBe('clamp_voltage: 5V');
  });

  it('renders diode reverse voltage and current without leaking compatibility voltage keys into Other', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'D310000014600',
        bom_me_score: 76.4,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'diode',
          materialName: '肖特基二极管',
          specModel: 'SS34 40V 3A SMA',
          packageCode: 'SMA',
          attributes: {
            model_text: 'SS34',
            reverse_voltage: '40V',
            reverse_voltage_volts: 40,
            voltage: '40V',
            voltage_volts: 40,
            current: '3A',
            current_amps: 3,
            clamp_voltage: '5V',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'diode',
      },
    });

    expect(fields.map((field) => field.key)).toEqual([
      'code',
      'score',
      'name',
      'spec',
      'package',
      'model',
      'reverse_voltage',
      'current',
      'other',
    ]);
    expect(fields.find((field) => field.key === 'reverse_voltage')?.fallbackFields).toEqual([
      'attributes.reverse_voltage_volts',
      'attributes.voltage',
      'attributes.voltage_volts',
    ]);
    expect(fields.find((field) => field.key === 'current')?.fallbackFields).toEqual([
      'attributes.current_amps',
    ]);
    expect(fields.find((field) => field.key === 'other')?.value).toBe('clamp_voltage: 5V');
  });

  it('renders LED color, wavelength, and current while keeping brightness as Other evidence', () => {
    const fields = resolveCandidateFieldColumns({
      item,
      candidate: {
        bom_me_material_code: 'DD20000001200',
        bom_me_score: 72.1,
        bom_me_candidate_snapshot_json: JSON.stringify({
          category: 'led',
          materialName: '贴片 LED',
          specModel: 'LED0603-G 520nm 20mA',
          packageCode: '0603',
          attributes: {
            color: 'green',
            wavelength: '520nm',
            wavelength_nm: 520,
            current: '20mA',
            current_amps: 0.02,
            brightness_mcd: '60mcd',
          },
        }),
      },
      referenceRecord: {
        bom_cl_category: 'led',
      },
    });

    expect(fields.map((field) => field.key)).toEqual([
      'code',
      'score',
      'name',
      'spec',
      'package',
      'color',
      'wavelength',
      'current',
      'other',
    ]);
    expect(fields.find((field) => field.key === 'wavelength')?.fallbackFields).toEqual([
      'attributes.wavelength_nm',
    ]);
    expect(fields.find((field) => field.key === 'current')?.fallbackFields).toEqual([
      'attributes.current_amps',
    ]);
    expect(fields.find((field) => field.key === 'other')?.value).toBe('brightness_mcd: 60mcd');
  });
});

describe('resolveProfiledFieldGroups', () => {
  it('splits common identity fields, category attributes, and Other attributes', () => {
    const groups = resolveProfiledFieldGroups({
      item: {
        fieldColumns: [
          { key: 'standard_code', field: 'bom_cl_current_standard_code' },
          { key: 'name', field: 'bom_cl_material_name' },
          { key: 'capacitance', sourceField: 'bom_cl_attributes_json', field: 'capacitance' },
          { key: 'voltage', sourceField: 'bom_cl_attributes_json', field: 'voltage' },
        ],
        fieldProfiles: {
          categoryField: 'bom_cl_category',
          attributeSourceField: 'bom_cl_attributes_json',
          profiles: {
            capacitor: [
              { key: 'capacitance', sourceField: 'bom_cl_attributes_json', field: 'capacitance' },
              { key: 'voltage', sourceField: 'bom_cl_attributes_json', field: 'voltage' },
            ],
          },
          otherField: {
            key: 'other',
            label: { 'zh-CN': '其他已抽取属性', en: 'Other Extracted Attributes' },
          },
        },
      },
      record: {
        bom_cl_category: 'capacitor',
        bom_cl_current_standard_code: 'D510000026700',
        bom_cl_material_name: '贴片电容',
        bom_cl_attributes_json: JSON.stringify({
          capacitance: '100nF',
          voltage: '50V',
          dielectric: 'X7R',
          temperature_range: '-55℃~+125℃',
        }),
      },
    });

    expect(groups.map((group) => group.key)).toEqual(['common', 'profile', 'other']);
    expect(groups[0].fields.map((field: any) => field.key)).toEqual(['standard_code', 'name']);
    expect(groups[1].fields.map((field: any) => field.key)).toEqual(['capacitance', 'voltage']);
    expect(groups[2].fields[0].value).toBe('dielectric: X7R；temperature_range: -55℃~+125℃');
  });

  it('uses the current record category when the reference record has no canonical category', () => {
    const groups = resolveProfiledFieldGroups({
      item: {
        fieldColumns: [
          { key: 'name', field: 'bom_cl_material_name' },
          { key: 'capacitance', sourceField: 'bom_cl_attributes_json', field: 'capacitance' },
        ],
        fieldProfiles: {
          categoryField: 'bom_cl_category',
          attributeSourceField: 'bom_cl_attributes_json',
          profiles: {
            capacitor: [
              { key: 'capacitance', sourceField: 'bom_cl_attributes_json', field: 'capacitance' },
            ],
          },
        },
      },
      record: {
        bom_cl_category: 'capacitor',
        bom_cl_material_name: '贴片电容',
        bom_cl_attributes_json: JSON.stringify({ capacitance: '100nF' }),
      },
      referenceRecord: {
        bom_std_category: 'capacitor',
      },
    });

    expect(groups.map((group) => group.key)).toEqual(['common', 'profile']);
    expect(groups[1].fields.map((field: any) => field.key)).toEqual(['capacitance']);
  });
});

describe('resolveProfiledFieldColumns', () => {
  it('uses the canonical resistor category to avoid capacitor-only fields in the standardized result', () => {
    const fields = resolveProfiledFieldColumns({
      item: {
        fieldColumns: [
          { key: 'standard_code', field: 'bom_cl_current_standard_code' },
          { key: 'name', field: 'bom_cl_material_name' },
          { key: 'spec', field: 'bom_cl_spec' },
          { key: 'package', field: 'bom_cl_package' },
          { key: 'capacitance', sourceField: 'bom_cl_attributes_json', field: 'capacitance' },
          { key: 'voltage', sourceField: 'bom_cl_attributes_json', field: 'voltage' },
          { key: 'dielectric', sourceField: 'bom_cl_attributes_json', field: 'material' },
        ],
        fieldProfiles: {
          categoryField: 'bom_cl_category',
          attributeSourceField: 'bom_cl_attributes_json',
          profiles: {
            resistor: [
              {
                key: 'resistance',
                sourceField: 'bom_cl_attributes_json',
                field: 'resistance',
              },
              {
                key: 'tolerance',
                sourceField: 'bom_cl_attributes_json',
                field: 'precision',
              },
            ],
            capacitor: [
              {
                key: 'capacitance',
                sourceField: 'bom_cl_attributes_json',
                field: 'capacitance',
              },
              {
                key: 'voltage',
                sourceField: 'bom_cl_attributes_json',
                field: 'voltage',
              },
              {
                key: 'dielectric',
                sourceField: 'bom_cl_attributes_json',
                field: 'material',
              },
            ],
          },
          otherField: {
            key: 'other',
            excludeKeys: ['package'],
          },
        },
      },
      record: {
        bom_cl_category: 'resistor',
        bom_cl_current_standard_code: '',
        bom_cl_material_name: '贴片电阻',
        bom_cl_spec: '贴片电阻 240Ω ±1% 0201',
        bom_cl_package: '0201',
        bom_cl_attributes_json: JSON.stringify({
          package: '0201',
          resistance: '240Ω',
          precision: '±1%',
          capacitance: '',
          voltage: '',
        }),
      },
    });

    expect(fields.map((field) => field.key)).toEqual([
      'standard_code',
      'name',
      'spec',
      'package',
      'resistance',
      'tolerance',
    ]);
    expect(fields.find((field) => field.key === 'capacitance')).toBeUndefined();
    expect(fields.find((field) => field.key === 'voltage')).toBeUndefined();
    expect(fields.find((field) => field.key === 'dielectric')).toBeUndefined();
    expect(fields.find((field) => field.key === 'other')).toBeUndefined();
  });
});
