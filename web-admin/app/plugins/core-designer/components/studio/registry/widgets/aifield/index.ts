/**
 * AiField widget definition
 *
 * AI-powered field that generates/summarizes/translates/classifies/extracts content.
 * NOTE BUG-4: runtime component lacks name/label/FieldBase integration — cannot
 * submit as standalone form field. Schema exposed with full intended props.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const aifieldWidget: WidgetDefinition = {
  component: 'aifield',
  name: 'AI Field',
  icon: '🤖',
  category: 'advanced',
  description: 'AI-generated content field (generate/summarize/translate/classify/extract)',
  schema: [
    {
      key: 'placeholder',
      label: 'Placeholder',
      type: 'text',
      group: 'AiField',
      defaultValue: 'AI-generated content...',
    },
    {
      key: 'aiConfig.operation',
      label: 'AI Operation',
      type: 'select',
      group: 'AI Config',
      options: [
        { label: 'Generate', value: 'generate' },
        { label: 'Summarize', value: 'summarize' },
        { label: 'Translate', value: 'translate' },
        { label: 'Classify', value: 'classify' },
        { label: 'Extract', value: 'extract' },
      ],
    },
    {
      key: 'aiConfig.prompt',
      label: 'Prompt Template',
      type: 'textarea',
      group: 'AI Config',
      description: 'Prompt template for AI generation',
    },
    {
      key: 'aiConfig.sourceFields',
      label: 'Source Fields (JSON)',
      type: 'json',
      group: 'AI Config',
      description: 'Array of field names to use as input context, e.g. ["title","description"]',
    },
    {
      key: 'aiConfig.targetLanguage',
      label: 'Target Language',
      type: 'text',
      group: 'AI Config',
      placeholder: 'en',
      description: 'Target language code for translate operation',
    },
    {
      key: 'aiConfig.categories',
      label: 'Categories (JSON)',
      type: 'json',
      group: 'AI Config',
      description: 'Array of category strings for classify operation',
    },
    {
      key: 'aiConfig.extractFields',
      label: 'Extract Fields (JSON)',
      type: 'json',
      group: 'AI Config',
      description: 'Array of field names to extract for extract operation',
    },
    {
      key: 'aiConfig.maxTokens',
      label: 'Max Tokens',
      type: 'number',
      group: 'AI Config',
      defaultValue: 500,
    },
    {
      key: 'aiConfig.temperature',
      label: 'Temperature',
      type: 'number',
      group: 'AI Config',
      defaultValue: 0.7,
      description: 'Sampling temperature 0.0-1.0 (lower = more deterministic)',
    },
    {
      key: 'modelCode',
      label: 'Model Code',
      type: 'text',
      group: 'Context',
      description: 'Record model code for context-aware AI fill',
    },
  ],
};
