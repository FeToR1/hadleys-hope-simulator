/** Human-readable form of a value held in a VM's private state (numbers, enums, Option, records). */
export function formatVmValue(value: unknown): string {
  if (value === null) return 'none';
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(3);
  if (typeof value === 'object' && !Array.isArray(value) && Object.keys(value as object).length === 1 && 'some' in (value as object)) {
    return `some(${formatVmValue((value as { some: unknown }).some)})`;
  }
  return typeof value === 'string' || typeof value === 'boolean' ? String(value) : JSON.stringify(value);
}
