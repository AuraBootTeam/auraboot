import { ensureModelListRenderersRegistered } from '~/plugins/core-meta/pages/meta/models/modelListRenderers';
import { ensureFieldListRenderersRegistered } from '~/plugins/core-meta/pages/meta/fields/fieldListRenderers';

let registered = false;

export function registerMetaPageRenderers() {
  if (registered) {
    return;
  }

  ensureModelListRenderersRegistered();
  ensureFieldListRenderersRegistered();

  registered = true;
}
