import type { ViewConfig, ViewType } from '~/framework/smart/types/savedView';

export type SavedViewCapabilityStatus = 'available' | 'degraded' | 'blocked';

export interface SavedViewCapabilityField {
  code: string;
  name: string;
  dataType?: string;
}

export interface SavedViewCapabilityResult {
  viewType: ViewType;
  status: SavedViewCapabilityStatus;
  reasons: string[];
  fieldOptions: Record<string, SavedViewCapabilityField[]>;
  suggestedConfig: Partial<ViewConfig>;
}

const GROUPABLE_TYPES = new Set([
  'boolean',
  'bool',
  'dict',
  'enum',
  'reference',
  'status',
  'string',
  'text',
  'user',
]);

const TEXT_TYPES = new Set(['string', 'text', 'textarea', 'richtext', 'varchar']);
const DATE_TYPES = new Set(['date', 'datetime', 'timestamp']);
const IMAGE_TYPES = new Set(['image', 'file', 'attachment', 'avatar', 'media']);
const TREE_PARENT_TYPES = new Set(['reference', 'relation', 'lookup']);

function normalizedDataType(field: SavedViewCapabilityField): string {
  return String(field.dataType ?? '').trim().toLowerCase();
}

function matchesType(
  field: SavedViewCapabilityField,
  acceptedTypes: Set<string>,
): boolean {
  return acceptedTypes.has(normalizedDataType(field));
}

function firstCode(fields: SavedViewCapabilityField[]): string | undefined {
  return fields[0]?.code;
}

function fieldSearchText(field: SavedViewCapabilityField): string {
  return `${field.code} ${field.name}`.toLowerCase();
}

function matchesImageField(field: SavedViewCapabilityField): boolean {
  if (matchesType(field, IMAGE_TYPES)) {
    return true;
  }
  return /\b(image|avatar|photo|picture|file|attachment|cover)\b/.test(fieldSearchText(field));
}

function matchesTreeParentField(field: SavedViewCapabilityField): boolean {
  if (matchesType(field, TREE_PARENT_TYPES)) {
    return true;
  }
  return /\b(parent|parentid|parent_id|path|level)\b/.test(fieldSearchText(field));
}

function result(
  viewType: ViewType,
  status: SavedViewCapabilityStatus,
  reasons: string[],
  fieldOptions: Record<string, SavedViewCapabilityField[]> = {},
  suggestedConfig: Partial<ViewConfig> = {},
): SavedViewCapabilityResult {
  return { viewType, status, reasons, fieldOptions, suggestedConfig };
}

export function checkSavedViewCapability(
  viewType: ViewType,
  fields: SavedViewCapabilityField[],
): SavedViewCapabilityResult {
  if (viewType === 'table' || viewType === 'form') {
    return result(viewType, 'available', []);
  }

  if (viewType === 'kanban') {
    const groupFields = fields.filter((field) => matchesType(field, GROUPABLE_TYPES));
    const titleFields = fields.filter((field) => matchesType(field, TEXT_TYPES));
    const fieldOptions = {
      groupByField: groupFields,
      titleField: titleFields,
    };

    if (groupFields.length === 0 || titleFields.length === 0) {
      return result(
        viewType,
        'blocked',
        [
          groupFields.length === 0
            ? 'Kanban requires a groupable status, enum, reference, user, boolean, or text field.'
            : 'Kanban requires a title text field.',
        ],
        fieldOptions,
      );
    }

    return result(
      viewType,
      'degraded',
      ['Drag is disabled until a status update command is configured.'],
      fieldOptions,
      {
        groupByField: firstCode(groupFields),
        titleField: firstCode(titleFields),
      },
    );
  }

  if (viewType === 'calendar') {
    const dateFields = fields.filter((field) => matchesType(field, DATE_TYPES));
    const titleFields = fields.filter((field) => matchesType(field, TEXT_TYPES));
    const fieldOptions = {
      calendarDateField: dateFields,
      calendarTitleField: titleFields,
    };

    if (dateFields.length === 0) {
      return result(
        viewType,
        'blocked',
        ['Calendar requires at least one date or datetime field.'],
        fieldOptions,
      );
    }

    return result(viewType, 'available', [], fieldOptions, {
      calendarDateField: firstCode(dateFields),
      calendarTitleField: firstCode(titleFields),
    });
  }

  if (viewType === 'gantt') {
    const dateFields = fields.filter((field) => matchesType(field, DATE_TYPES));
    const titleFields = fields.filter((field) => matchesType(field, TEXT_TYPES));
    const fieldOptions = {
      ganttStartDateField: dateFields,
      ganttEndDateField: dateFields,
      ganttTitleField: titleFields,
    };

    if (dateFields.length === 0) {
      return result(
        viewType,
        'blocked',
        ['Gantt requires at least one date or datetime field for start and end dates.'],
        fieldOptions,
      );
    }

    const [startField, endField = startField] = dateFields;
    const status = dateFields.length === 1 ? 'degraded' : 'available';
    const reasons =
      dateFields.length === 1
        ? ['Only one date field is available; it will be reused as both start and end date.']
        : [];

    return result(viewType, status, reasons, fieldOptions, {
      ganttStartDateField: startField.code,
      ganttEndDateField: endField.code,
      ganttTitleField: firstCode(titleFields),
    });
  }

  if (viewType === 'gallery') {
    const imageFields = fields.filter(matchesImageField);
    const titleFields = fields.filter((field) => matchesType(field, TEXT_TYPES));
    const fieldOptions = {
      galleryImageField: imageFields,
      galleryTitleField: titleFields,
    };

    if (imageFields.length === 0) {
      return result(
        viewType,
        'blocked',
        ['Gallery requires an image, file, attachment, avatar, or cover field.'],
        fieldOptions,
      );
    }

    return result(viewType, 'available', [], fieldOptions, {
      galleryImageField: firstCode(imageFields),
      galleryTitleField: firstCode(titleFields),
    });
  }

  if (viewType === 'tree') {
    const parentFields = fields.filter(matchesTreeParentField);
    const titleFields = fields.filter((field) => matchesType(field, TEXT_TYPES));
    const fieldOptions = {
      treeParentField: parentFields,
      treeTitleField: titleFields,
    };

    if (parentFields.length === 0) {
      return result(
        viewType,
        'blocked',
        ['Tree requires a parent, path, or level field to build the hierarchy.'],
        fieldOptions,
      );
    }

    return result(
      viewType,
      'degraded',
      ['Reorder is disabled until a tree update command is configured.'],
      fieldOptions,
      {
        treeParentField: firstCode(parentFields),
        treeTitleField: firstCode(titleFields),
      },
    );
  }

  if (viewType === 'timeline') {
    const dateFields = fields.filter((field) => matchesType(field, DATE_TYPES));
    const resourceFields = fields.filter((field) => matchesType(field, GROUPABLE_TYPES));
    const titleFields = fields.filter((field) => matchesType(field, TEXT_TYPES));
    const fieldOptions = {
      timelineStartField: dateFields,
      timelineEndField: dateFields,
      timelineResourceField: resourceFields,
      timelineTitleField: titleFields,
    };

    if (dateFields.length === 0 || resourceFields.length === 0) {
      return result(
        viewType,
        'blocked',
        [
          dateFields.length === 0
            ? 'Timeline requires at least one date or datetime start field.'
            : 'Timeline requires a resource field for swim lanes.',
        ],
        fieldOptions,
      );
    }

    const [startField, endField] = dateFields;
    return result(viewType, 'available', [], fieldOptions, {
      timelineStartField: startField.code,
      timelineEndField: endField?.code,
      timelineResourceField: firstCode(resourceFields),
      timelineTitleField: firstCode(titleFields),
    });
  }

  return result(viewType, 'available', []);
}
