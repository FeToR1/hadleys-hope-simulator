import type { EntityDelta, EntityLog, EntityState, StateSnapshot, TickBatch } from './types';

type StateListener = (snapshot: StateSnapshot, deltas: readonly EntityDelta[]) => void;
export interface StateSubscriptionOptions {
  throttleMs?: number;
  animationFrame?: boolean;
}

interface ListenerRecord {
  listener: StateListener;
  options: StateSubscriptionOptions;
  timer?: ReturnType<typeof setTimeout>;
  frame?: number;
  latest?: { snapshot: StateSnapshot; deltas: readonly EntityDelta[] };
}

const copyEntityInto = (target: EntityState, source: EntityState): boolean => {
  const connectionsChanged =
    target.connectedTo.length !== source.connectedTo.length ||
    target.connectedTo.some((connection, index) => connection !== source.connectedTo[index]);
  target.pid = source.pid;
  target.type = source.type;
  target.status = source.status;
  target.metrics = source.metrics;
  target.coordinates.x = source.coordinates.x;
  target.coordinates.y = source.coordinates.y;
  target.parentId = source.parentId;
  if (connectionsChanged) {
    target.connectedTo.length = 0;
    target.connectedTo.push(...source.connectedTo);
  }
  return connectionsChanged;
};

export class StateManager {
  private readonly entities = new Map<string, EntityState>();
  private readonly listeners = new Set<ListenerRecord>();
  private readonly logs: EntityLog[] = [];
  private tickId = 0;
  private timestamp = 0;
  private nextLogId = 1;
  private runId = '';
  private revision = 0;
  private seed = '';
  private runtimeMode: StateSnapshot['runtimeMode'] = 'mock';

  public reset(): void {
    this.entities.clear();
    this.logs.length = 0;
    this.tickId = -1;
    this.timestamp = 0;
    this.runId = '';
    this.seed = '';
    this.revision += 1;
    for (const record of this.listeners) {
      if (record.timer !== undefined) clearTimeout(record.timer);
      if (record.frame !== undefined) {
        if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(record.frame);
        else clearTimeout(record.frame);
      }
      record.timer = undefined;
      record.frame = undefined;
      record.latest = undefined;
      record.listener(this.snapshot(), []);
    }
  }

  public ingest(batch: TickBatch): void {
    let topologyChanged = false;
    const incomingRun = batch.runId ?? 'mock';
    if (incomingRun !== this.runId) {
      this.reset();
      this.runId = incomingRun;
    } else if (batch.tickId <= this.tickId) return;
    this.seed = batch.seed ?? 'lv426-demo-2026';
    this.runtimeMode = batch.runtimeMode ?? 'mock';
    if (batch.full) {
      const present = new Set(batch.entities.map((entity) => entity.id));
      let removed = false;
      for (const id of this.entities.keys()) {
        if (!present.has(id)) { this.entities.delete(id); removed = true; }
      }
      if (removed) topologyChanged = true;
    }
    const deltas: EntityDelta[] = [];
    for (const incoming of batch.entities) {
      const existing = this.entities.get(incoming.id);
      if (existing === undefined) {
        topologyChanged = true;
        const stored: EntityState = {
          ...incoming,
          metrics: incoming.metrics,
          connectedTo: [...incoming.connectedTo],
          coordinates: { ...incoming.coordinates },
        };
        this.entities.set(incoming.id, stored);
        deltas.push({ entity: stored, connectionsChanged: true });
        continue;
      }
      const changed =
        existing.type !== incoming.type ||
        existing.status !== incoming.status ||
        existing.pid !== incoming.pid ||
        existing.coordinates.x !== incoming.coordinates.x ||
        existing.coordinates.y !== incoming.coordinates.y ||
        existing.metrics !== incoming.metrics ||
        existing.parentId !== incoming.parentId;
      if (existing.type !== incoming.type) topologyChanged = true;
      const connectionsChanged = copyEntityInto(existing, incoming);
      if (connectionsChanged) topologyChanged = true;
      if (changed || connectionsChanged) deltas.push({ entity: existing, connectionsChanged });
    }
    if (topologyChanged) this.revision += 1;
    this.tickId = batch.tickId;
    this.timestamp = batch.timestamp;
    const snapshot = this.snapshot();
    for (const record of this.listeners) {
      const payload = { snapshot, deltas };
      if (record.options.animationFrame) {
        record.latest = payload;
        if (record.frame === undefined) {
          const run = (): void => {
            record.frame = undefined;
            const latest = record.latest;
            record.latest = undefined;
            if (latest !== undefined && this.listeners.has(record)) record.listener(latest.snapshot, latest.deltas);
          };
          record.frame = typeof requestAnimationFrame === 'function' ? requestAnimationFrame(run) : Number(setTimeout(run, 16));
        }
      } else if (record.options.throttleMs !== undefined) {
        record.latest = payload;
        if (record.timer === undefined) {
          record.timer = setTimeout(() => {
            record.timer = undefined;
            const latest = record.latest;
            record.latest = undefined;
            if (latest !== undefined && this.listeners.has(record)) record.listener(latest.snapshot, latest.deltas);
          }, record.options.throttleMs);
        }
      } else {
        record.listener(snapshot, deltas);
      }
    }
  }

  public appendLog(log: Omit<EntityLog, 'id'>): void {
    this.logs.push({ ...log, id: this.nextLogId++ });
    if (this.logs.length > 200) this.logs.shift();
  }

  public getEntity(id: string): EntityState | undefined {
    return this.entities.get(id);
  }

  public subscribe(listener: StateListener, options: StateSubscriptionOptions = {}): () => void {
    const record: ListenerRecord = { listener, options };
    this.listeners.add(record);
    return () => {
      if (record.timer !== undefined) clearTimeout(record.timer);
      if (record.frame !== undefined && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(record.frame);
      else if (record.frame !== undefined) clearTimeout(record.frame);
      this.listeners.delete(record);
    };
  }

  public snapshot(): StateSnapshot {
    return { runId: this.runId, revision: this.revision, seed: this.seed, runtimeMode: this.runtimeMode,
      tickId: this.tickId, timestamp: this.timestamp, entities: this.entities, logs: this.logs };
  }
}
