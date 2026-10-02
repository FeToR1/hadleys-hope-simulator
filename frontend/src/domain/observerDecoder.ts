import { parseObserverEntity, parseObserverEnvelope, parseTickBatch } from './dataSource';
import type { EntityState, TickBatch } from './types';

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const allowed = new Set(['id', 'pid', 'type', 'status', 'metrics', 'connectedTo', 'coordinates', 'parentId', 'vmState']);

/** Validate a complete tick before committing its baseline; unchanged entities retain identity. */
export class ObserverDecoder {
  private previous?: TickBatch;
  private entities = new Map<string, EntityState>();
  reset(): void { this.previous = undefined; this.entities.clear(); }
  decode(value: unknown): TickBatch {
    if (!record(value) || value.version !== 2) return parseTickBatch(value);
    if (typeof value.full !== 'boolean' || !Array.isArray(value.entities) || !Array.isArray(value.removed) ||
      !value.removed.every(id => typeof id === 'string')) throw new Error('Invalid compact snapshot');
    if (!value.full && (!this.previous || value.runId !== this.previous.runId || value.baseTick !== this.previous.tickId ||
      value.tickId !== this.previous.tickId + 1)) throw new Error('Missing compact baseline');
    const envelope = parseObserverEnvelope({ ...value, version: 1, full: true });
    const changed = new Map<string, EntityState>();
    for (const patch of value.entities) {
      if (!record(patch) || typeof patch.id !== 'string' || changed.has(patch.id) || Object.keys(patch).some(key => !allowed.has(key))) {
        throw new Error('Invalid compact entity');
      }
      const before = value.full ? undefined : this.entities.get(patch.id);
      changed.set(patch.id, parseObserverEntity({ ...before, ...patch }));
    }
    const removed = new Set<string>();
    for (const id of value.removed as string[]) {
      if (changed.has(id) || removed.has(id) || value.full || !this.entities.has(id)) throw new Error('Invalid removed entity');
      removed.add(id);
    }
    // All untrusted input is validated before changing the committed index.
    if (value.full) this.entities.clear();
    for (const [id, entity] of changed) this.entities.set(id, entity);
    for (const id of removed) this.entities.delete(id);
    const reconstructed: TickBatch = { ...envelope, entities: [...this.entities.values()] };
    this.previous = reconstructed;
    return reconstructed;
  }
}
