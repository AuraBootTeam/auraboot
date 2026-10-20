/** Install before navigation. Record browser event time, never the time a test runner notices readiness. */
export function installHomeReadiness() {
  const marks = { checks: 0 };
  window.__perfHome = marks;
  const visible = element => Boolean(element && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden');
  const testId = id => document.querySelector(`[data-testid="${id}"]`);
  const zero = id => {
    const card = testId(id);
    return visible(card) && Array.from(card.children).some(child => visible(child) && child.textContent.trim() === '0');
  };
  let scheduled = false;
  const observer = new MutationObserver(schedule);
  function check() {
    scheduled = false;
    marks.checks++;
    const now = performance.now();
    const record = (name, ready) => { if (ready && marks[name] === undefined) marks[name] = now; };
    record('structure', visible(testId('workbench-open-in-dashboard')) && visible(document.querySelector('.react-grid-item')));
    record('inbox', Array.from(document.querySelectorAll('td')).some(td => visible(td) && td.textContent.trim() === '暂无待办事项'));
    record('shortcuts', visible(testId('shortcut-row')));
    record('stats', zero('stat-card-inbox_pending') && zero('stat-card-inbox_urgent'));
    if (['structure', 'inbox', 'shortcuts', 'stats'].every(key => marks[key] !== undefined)) {
      marks.ready = Math.max(marks.structure, marks.inbox, marks.shortcuts, marks.stats);
      performance.mark('oss.home.data-ready', { startTime: marks.ready });
      observer.disconnect();
    }
  }
  function schedule() { if (!scheduled) { scheduled = true; requestAnimationFrame(check); } }
  observer.observe(document, { subtree: true, childList: true, attributes: true, characterData: true });
  schedule();
}
