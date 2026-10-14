/** UTC text only, rejecting Date.parse's silent calendar rollover. */
export function isUtcInstant(value: string) {
  if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(value)) return false;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) && timestamp >= 0 && new Date(timestamp).toISOString().slice(0, 19) === value.slice(0, 19);
}
