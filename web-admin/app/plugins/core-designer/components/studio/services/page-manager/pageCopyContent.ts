import type { PageSchemaCreateRequest, PageSchemaDTO } from './api-types';
import { hasTopLevelKindRoot, materializeStoredPageSchemaV3 } from '../../../unified-designer/persistence/pageSchemaV3Repository';
import { FLAT_PAGE_SCHEMA_VERSION, serializePageTreeToFlat } from '../../../unified-designer/persistence/flatPageSerializer';

type CopyContent = Pick<PageSchemaCreateRequest, 'kind' | 'blocks' | 'layout' | 'modelCode' |
  'profile' | 'dataSources' | 'extension' | 'schemaVersion'>;

/** Copy stored content through the existing format boundary before creating a page. */
export function preparePageCopyContent(source: PageSchemaDTO): CopyContent {
  const content = { kind: source.kind, blocks: source.blocks ?? [], layout: source.layout,
    modelCode: source.modelCode, profile: source.profile, dataSources: source.dataSources,
    extension: source.extension, schemaVersion: FLAT_PAGE_SCHEMA_VERSION };
  if (source.schemaVersion === FLAT_PAGE_SCHEMA_VERSION && !hasTopLevelKindRoot(source)) return content;
  const flat = serializePageTreeToFlat(materializeStoredPageSchemaV3(source));
  return { ...content, kind: flat.kind, blocks: flat.blocks,
    extension: flat.rootBlockId ? { ...source.extension, designerRootId: flat.rootBlockId } : source.extension };
}
