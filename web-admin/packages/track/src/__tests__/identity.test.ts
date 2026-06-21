import { deriveUiElement } from '../identity';

it('derives stable identity from data-aura-element-id ancestor', () => {
  document.body.innerHTML = `<div data-aura-element-id="elm_1" data-aura-page-id="p1" data-aura-block-id="b1"><button id="x">Go</button></div>`;
  const ui = deriveUiElement(document.getElementById('x')!);
  expect(ui).toEqual(expect.objectContaining({ uiElementId: 'elm_1', pageId: 'p1', blockId: 'b1', identityQuality: 'stable' }));
});

it('NEVER captures input values', () => {
  document.body.innerHTML = `<input id="pw" value="secret" />`;
  const ui = deriveUiElement(document.getElementById('pw')!);
  // heuristic (no element-id ancestor) — must carry no value/innerHTML
  expect(JSON.stringify(ui ?? {})).not.toContain('secret');
});

it('returns heuristic quality when no data-aura-element-id ancestor', () => {
  document.body.innerHTML = `<button id="btn" role="button">Click</button>`;
  const ui = deriveUiElement(document.getElementById('btn')!);
  expect(ui).toBeDefined();
  expect(ui!.identityQuality).toBe('heuristic');
  expect(ui!.uiElementId).toContain('heuristic:');
});

it('includes appId and elementCode when present on host', () => {
  document.body.innerHTML = `<div data-aura-element-id="elm_2" data-aura-app-id="app1" data-aura-element-code="save_btn"><span id="inner">Save</span></div>`;
  const ui = deriveUiElement(document.getElementById('inner')!);
  expect(ui).toEqual(expect.objectContaining({
    uiElementId: 'elm_2',
    appId: 'app1',
    elementCode: 'save_btn',
    identityQuality: 'stable',
  }));
});

it('heuristic path never leaks textContent', () => {
  // Element with visible text content but no tracking ancestor —
  // the heuristic branch must not capture any textContent.
  document.body.innerHTML = `<div id="bare">text</div>`;
  const ui = deriveUiElement(document.getElementById('bare')!);
  // heuristic path — must not throw and must not leak textContent
  expect(JSON.stringify(ui ?? {})).not.toContain('text');
});
