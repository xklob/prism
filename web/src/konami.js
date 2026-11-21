// Keyboard sequence and touch toggle from the klob Standard Design kit.
const sequence = [
  'arrowup',
  'arrowup',
  'arrowdown',
  'arrowdown',
  'arrowleft',
  'arrowright',
  'arrowleft',
  'arrowright',
  'b',
  'a',
];
const entered = [];
const valueControls =
  'input, textarea, select, [contenteditable]:not([contenteditable="false"]), ' +
  '[role="textbox"], [role="slider"], [role="menu"], [role="tablist"], ' +
  '[role="radiogroup"], [role="spinbutton"]';
const root = document.documentElement;
const status = document.querySelector('[data-konami-status]');

function setRainbow(enabled) {
  root.classList.toggle('psychedelic', enabled);
  document.querySelector('[data-konami-toggle]')?.setAttribute('aria-pressed', String(enabled));
  status.textContent = enabled ? 'Rainbow effect on. Press Escape to exit.' : 'Rainbow effect off.';
  entered.length = 0;
}
document.addEventListener('click', (event) => {
  if (event.target.closest?.('[data-konami-toggle]')) setRainbow(!root.classList.contains('psychedelic'));
});
document.addEventListener('keydown', (event) => {
  if (event.repeat) return;
  if (
    event.defaultPrevented ||
    event.isComposing ||
    event.altKey ||
    event.ctrlKey ||
    event.metaKey ||
    event.shiftKey ||
    document.querySelector('dialog[open], [aria-haspopup][aria-expanded="true"]')
  ) {
    entered.length = 0;
    return;
  }
  if (event.key === 'Escape') {
    entered.length = 0;
    if (root.classList.contains('psychedelic')) setRainbow(false);
    return;
  }
  if (document.body.classList.contains('immersive') || event.target.closest?.(valueControls)) {
    entered.length = 0;
    return;
  }

  entered.push(event.key.toLowerCase());
  while (entered.length && !entered.every((key, index) => key === sequence[index])) entered.shift();
  if (entered.length >= 2 && event.key.startsWith('Arrow')) event.preventDefault();
  if (entered.length === sequence.length) setRainbow(!root.classList.contains('psychedelic'));
});
window.addEventListener('blur', () => {
  entered.length = 0;
});
