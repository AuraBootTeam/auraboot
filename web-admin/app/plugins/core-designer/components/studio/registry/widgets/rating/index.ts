/**
 * Rating widget definition
 *
 * Star rating input for integer fields. Clicking the same star again clears the
 * value. No half-star support.
 *
 * @since 4.4.0
 */

import type { WidgetDefinition } from '../../types';

export const ratingWidget: WidgetDefinition = {
  component: 'rating',
  name: 'Rating',
  icon: '★',
  category: 'input',
  description: 'Star rating picker',
  schema: [
    {
      key: 'maxRating',
      label: 'Max Stars',
      type: 'number',
      group: 'Rating',
      defaultValue: 5,
    },
    {
      key: 'size',
      label: 'Star Size (px)',
      type: 'number',
      group: 'Rating',
      defaultValue: 20,
    },
  ],
};
