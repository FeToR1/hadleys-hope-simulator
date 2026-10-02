import { parseTickBatch } from './dataSource';
import type { TickBatch } from './types';

/** Reconstruct and validate a complete tick before mutating application state. */
export class ObserverDecoder {
  private previous?: TickBatch;
  reset(): void { this.previous = undefined; }
  decode(value: unknown): TickBatch {
    const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
    if (!record(value) || value.version !== 2) return parseTickBatch(value);
    if (typeof value.full !== 'boolean' || !Array.isArray(value.entities) || !Array.isArray(value.removed) ||
      !value.removed.every(id => typeof id === 'string')) throw new Error('Invalid compact snapshot');
    if (!value.full && (!this.previous || value.runId !== this.previous.runId || value.baseTick !== this.previous.tickId ||
      value.tickId !== this.previous.tickId + 1)) throw new Error('Missing compact baseline');
    const entities = new Map(value.full ? [] : this.previous!.entities.map(entity => [entity.id, entity] as const));
    const changed = new Set<string>();
    const allowed = new Set(['id', 'pid', 'type', 'status', 'metrics', 'connectedTo', 'coordinates', 'parentId', 'vmState']);
    for (const patch of value.entities) {
      if (!record(patch) || typeof patch.id !== 'string' || changed.has(patch.id) || Object.keys(patch).some(key => !allowed.has(key))) {
        throw new Error('Invalid compact entity');
      }
      changed.add(patch.id);
      entities.set(patch.id, { ...entities.get(patch.id), ...patch } as TickBatch['entities'][number]);
    }
    for (const id of value.removed as string[]) {
      if (changed.has(id) || !entities.delete(id)) throw new Error('Invalid removed entity');
    }
    const reconstructed = parseTickBatch({ ...value, version: 1, full: true, entities: [...entities.values()] });
    this.previous = reconstructed;
    return reconstructed;
  }
}
