import React from 'react';
import { KeysToComponentMap } from '~/pages/utils/ComponentMapping';

interface ComponentConfig {
  type: string;
  props?: Record<string, any>;
  layout?: any;
  key?: string | number;
}

export default function ComponentRender(config: ComponentConfig, count: number) {
  const component = config.type;
  let props = config.props || {};
  props.key = props.key || count;

  // 添加布局相关的props
  if (config.layout) {
    props.layoutConfig = config.layout;
  }

  let type = KeysToComponentMap[component as keyof typeof KeysToComponentMap];

  if (typeof type !== 'undefined') {
    return React.createElement(type, props);
  } else {
    return null;
  }
}
