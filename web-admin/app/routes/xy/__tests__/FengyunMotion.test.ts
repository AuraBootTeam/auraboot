import { describe, expect, it } from 'vitest';
import { motionPacketForAsset } from '../FengyunMotion';

describe('motionPacketForAsset', () => {
  it('maps themed bee and treehouse SVGs to their motion packets', () => {
    expect(motionPacketForAsset('/static/xiaoya/themes/reading/stage_3.svg')).toBe(
      '/static/xiaoya/motion/bees/reading/stage_3/animation.json',
    );
    expect(motionPacketForAsset('/static/xiaoya/home/stage_4.svg')).toBe(
      '/static/xiaoya/motion/home/stage_4/animation.json',
    );
  });

  it('maps the class tree and keeps unsupported legacy artwork on the image path', () => {
    expect(motionPacketForAsset('/static/xiaoya/bee-default-stage_1.svg')).toBeNull();
    expect(motionPacketForAsset('/static/xiaoya/bee-tree-stage-5.svg')).toBe(
      '/static/xiaoya/motion/tree/stage_5/animation.json',
    );
    expect(motionPacketForAsset(null)).toBeNull();
  });

  it('keeps refreshed raster artwork on the static image path', () => {
    expect(motionPacketForAsset('/static/xiaoya/themes/reading/stage_3.png')).toBeNull();
    expect(motionPacketForAsset('/static/xiaoya/class-tree/stage_5.png')).toBeNull();
  });
});
