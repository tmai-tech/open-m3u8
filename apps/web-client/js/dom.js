export const $ = (id) => document.getElementById(id);

export function bind(id, ev, fn) {
  const el = $(id);
  if (!el) return;
  el.addEventListener(ev, fn);
}
