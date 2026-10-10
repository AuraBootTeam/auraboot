import { useI18n } from '~/contexts/I18nContext';
import { publicValueType, type PublicField, type PublicType } from '~/services/open-api-publications';

const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const BUTTON = 'rounded-control border border-border-strong px-3 py-2 text-sm text-text focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const TYPES: PublicType[] = ['string', 'pid', 'integer', 'decimal', 'boolean', 'date', 'datetime', 'object', 'array'];

/** Structured property whitelist editor; source paths never become a free-form JSON payload. */
export function PublicValueSchemaEditor({ field, onChange, depth = 0 }: { field: PublicField; onChange: (field: PublicField) => void; depth?: number }) {
  const { t, locale } = useI18n();
  const label = (key: string, cn: string, en: string) => t(`openPlatform.publications.${key}`, undefined, locale.startsWith('zh') ? cn : en);
  const valueEditor = (value: PublicField, update: (value: PublicField) => void) => <div className="space-y-3">
    <label className="block space-y-1 text-sm"><span>{label('valueType', '值类型', 'Value type')}</span><select className={CONTROL} value={value.type} onChange={event => update(publicValueType(value, event.target.value as PublicType))}>
      {TYPES.filter(type => depth < 6 || !['object', 'array'].includes(type)).map(type => <option key={type} value={type}>{type}</option>)}</select></label>
    <div className="flex flex-wrap gap-3">{(['readable', 'writable', 'required', 'nullable'] as const).map(flag => <label key={flag} className="flex items-center gap-1 text-sm"><input type="checkbox" checked={value[flag]}
      onChange={event => update({ ...value, [flag]: event.target.checked })} />{({ readable: label('readable', '可读', 'Readable'), writable: label('writable', '可写', 'Writable'), required: label('required', '创建必填', 'Required on create'), nullable: label('nullable', '接受空值', 'Nullable') })[flag]}</label>)}</div>
    <PublicValueSchemaEditor field={value} onChange={update} depth={depth + 1} />
  </div>;
  if (field.type === 'array' && field.items) return <div className="space-y-3 rounded-control border border-border bg-subtle p-3"><h3 className="text-sm font-medium text-text">{label('arrayItems', '数组元素合同', 'Array item contract')}</h3>
    <p className="text-xs text-text-2">{label('arrayHelp', '每个元素遵守相同类型与属性白名单，最多 1000 项。', 'Every item follows the same type and property whitelist, up to 1,000 items.')}</p>
    {valueEditor(field.items, items => onChange({ ...field, items }))}</div>;
  if (field.type !== 'object') return null;
  return <div className="space-y-3 rounded-control border border-border bg-subtle p-3"><h3 className="text-sm font-medium text-text">{label('properties', '对象属性白名单', 'Object property whitelist')}</h3>
    <p className="text-xs text-text-2">{label('propertiesHelp', '声明原对象中要公开的属性及对外名称，未声明属性不会返回且写入会被拒绝。', 'Declare source properties and public names. Undeclared properties are omitted on reads and rejected on writes.')}</p>
    {Object.entries(field.properties).map(([alias, value], index) => {
      const update = (next: PublicField) => onChange({ ...field, properties: { ...field.properties, [alias]: next } });
      return <div key={index} className="space-y-3 rounded-control border border-border bg-panel p-3"><div className="grid gap-3 md:grid-cols-2">
        <label className="block space-y-1 text-sm"><span>{label('propertySource', '原对象属性', 'Source property')}</span><input required pattern="[A-Za-z][A-Za-z0-9_]{0,127}" className={CONTROL} value={value.source} placeholder="contact_name" onChange={event => update({ ...value, source: event.target.value })} /></label>
        <label className="block space-y-1 text-sm"><span>{label('propertyAlias', '对外属性名称', 'Public property name')}</span><input required pattern="[a-zA-Z][a-zA-Z0-9]{0,99}" className={CONTROL} value={alias} placeholder="contactName" onChange={event => {
          const next = event.target.value; if (Object.hasOwn(field.properties, next)) return;
          const properties = Object.fromEntries(Object.entries(field.properties).map(([key, entry]) => [key === alias ? next : key, entry])); onChange({ ...field, properties });
        }} /></label></div>
        {valueEditor(value, update)}
        <button type="button" className={`${BUTTON} text-danger`} onClick={() => { const properties = { ...field.properties }; delete properties[alias]; onChange({ ...field, properties }); }}>{label('removeProperty', '移除属性', 'Remove property')}</button>
      </div>;
    })}
    <button type="button" className={BUTTON} disabled={depth >= 7 || Object.keys(field.properties).length >= 100} onClick={() => {
      let index = 1; while (Object.hasOwn(field.properties, `property${index}`)) index++;
      const name = `property${index}`; onChange({ ...field, properties: { ...field.properties, [name]: { source: name, type: 'string', readable: field.readable, writable: field.writable,
        required: false, nullable: true, operators: [], sortable: false, referenceResource: null, properties: {}, items: null } } });
    }}>{label('addProperty', '添加属性', 'Add property')}</button>
  </div>;
}
