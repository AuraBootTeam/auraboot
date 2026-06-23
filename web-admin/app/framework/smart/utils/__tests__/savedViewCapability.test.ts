import { describe, expect, it } from 'vitest';
import { checkSavedViewCapability } from '../savedViewCapability';

describe('savedViewCapability', () => {
  it('blocks calendar creation when no date fields exist', () => {
    const result = checkSavedViewCapability('calendar', [
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('blocked');
    expect(result.reasons[0]).toContain('date');
    expect(result.fieldOptions.calendarDateField).toHaveLength(0);
  });

  it('marks kanban as degraded when it can group but lacks drag command capability', () => {
    const result = checkSavedViewCapability('kanban', [
      { code: 'status', name: 'Status', dataType: 'dict' },
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('degraded');
    expect(result.reasonCodes).toEqual(['kanban_drag_command_missing']);
    expect(result.fieldOptions.groupByField).toHaveLength(2);
    expect(result.fieldOptions.titleField).toHaveLength(1);
    expect(result.reasons.join(' ')).toContain('Drag');
  });

  it('prioritizes semantic status fields over generic string fields for kanban grouping', () => {
    const result = checkSavedViewCapability('kanban', [
      { code: 'order_no', name: '订单编号', dataType: 'string' },
      { code: 'order_status', name: '订单状态', dataType: 'dict' },
      { code: 'customer_name', name: '客户名称', dataType: 'string' },
      { code: 'order_title', name: '订单标题', dataType: 'text' },
    ]);

    expect(result.status).toBe('degraded');
    expect(result.fieldOptions.groupByField.map((field) => field.code).slice(0, 2)).toEqual([
      'order_status',
      'customer_name',
    ]);
    expect(result.suggestedConfig.groupByField).toBe('order_status');
    expect(result.suggestedConfig.titleField).toBe('order_title');
  });

  it('exposes stable reason codes for localized blocked messages', () => {
    const result = checkSavedViewCapability('calendar', [
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('blocked');
    expect(result.reasonCodes).toEqual(['missing_date_field']);
  });

  it('marks gantt as degraded with a single date field but still provides mappings', () => {
    const result = checkSavedViewCapability('gantt', [
      { code: 'dueDate', name: 'Due Date', dataType: 'date' },
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('degraded');
    expect(result.fieldOptions.ganttStartDateField).toHaveLength(1);
    expect(result.fieldOptions.ganttEndDateField).toHaveLength(1);
    expect(result.suggestedConfig.ganttStartDateField).toBe('dueDate');
    expect(result.suggestedConfig.ganttEndDateField).toBe('dueDate');
  });

  it('blocks gallery creation when no image, file, or avatar fields exist', () => {
    const result = checkSavedViewCapability('gallery', [
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('blocked');
    expect(result.reasons[0]).toContain('image');
    expect(result.fieldOptions.galleryImageField).toHaveLength(0);
  });

  it('suggests gallery image and title field mappings', () => {
    const result = checkSavedViewCapability('gallery', [
      { code: 'cover', name: 'Cover', dataType: 'image' },
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('available');
    expect(result.fieldOptions.galleryImageField).toHaveLength(1);
    expect(result.fieldOptions.galleryTitleField).toHaveLength(1);
    expect(result.suggestedConfig.galleryImageField).toBe('cover');
    expect(result.suggestedConfig.galleryTitleField).toBe('name');
  });

  it('blocks tree creation when no parent field exists', () => {
    const result = checkSavedViewCapability('tree', [
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('blocked');
    expect(result.reasons[0]).toContain('parent');
    expect(result.fieldOptions.treeParentField).toHaveLength(0);
  });

  it('marks tree as degraded when hierarchy fields exist but reorder command capability is unknown', () => {
    const result = checkSavedViewCapability('tree', [
      { code: 'parentId', name: 'Parent', dataType: 'reference' },
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('degraded');
    expect(result.fieldOptions.treeParentField).toHaveLength(1);
    expect(result.fieldOptions.treeTitleField).toHaveLength(1);
    expect(result.suggestedConfig.treeParentField).toBe('parentId');
    expect(result.suggestedConfig.treeTitleField).toBe('name');
    expect(result.reasons.join(' ')).toContain('Reorder');
  });

  it('blocks timeline creation when no date fields exist', () => {
    const result = checkSavedViewCapability('timeline', [
      { code: 'assignee', name: 'Assignee', dataType: 'user' },
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('blocked');
    expect(result.reasons[0]).toContain('date');
    expect(result.fieldOptions.timelineStartField).toHaveLength(0);
    expect(result.fieldOptions.timelineResourceField).toHaveLength(2);
  });

  it('blocks timeline creation when no resource field exists', () => {
    const result = checkSavedViewCapability('timeline', [
      { code: 'startDate', name: 'Start Date', dataType: 'date' },
    ]);

    expect(result.status).toBe('blocked');
    expect(result.reasons[0]).toContain('resource');
    expect(result.fieldOptions.timelineStartField).toHaveLength(1);
    expect(result.fieldOptions.timelineResourceField).toHaveLength(0);
  });

  it('suggests timeline date, resource, and title mappings', () => {
    const result = checkSavedViewCapability('timeline', [
      { code: 'startDate', name: 'Start Date', dataType: 'date' },
      { code: 'endDate', name: 'End Date', dataType: 'datetime' },
      { code: 'assignee', name: 'Assignee', dataType: 'user' },
      { code: 'name', name: 'Name', dataType: 'text' },
    ]);

    expect(result.status).toBe('available');
    expect(result.suggestedConfig.timelineStartField).toBe('startDate');
    expect(result.suggestedConfig.timelineEndField).toBe('endDate');
    expect(result.suggestedConfig.timelineResourceField).toBe('assignee');
    expect(result.suggestedConfig.timelineTitleField).toBe('name');
  });
});
