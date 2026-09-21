import { describe, expect, it, vi } from 'vitest';
import { LiveBrokerSource, parseHealth, parseTickBatch } from './dataSource';

describe('observer protocol v1', () => {
  const batch = {
    version: 1, runId: 'test', runtimeMode: 'reference', seed: '426', full: true,
    tickId: 0, timestamp: 1000,
    entities: [{ id: 'home-1/house', pid: null, type: 'house', status: 'nominal', metrics: { temperature: 16 },
      connectedTo: [], coordinates: { x: 0, y: 0 }, parentId: null }],
  };
  it('accepts reference snapshots and normalizes null parent IDs', () => {
    expect(parseTickBatch(batch).entities[0].parentId).toBeUndefined();
    expect(parseTickBatch(batch).entities[0].pid).toBeNull();
  });
  it('keeps VM state and effects, and shows kinds it does not know as generic objects', () => {
    const parsed = parseTickBatch({
      ...batch,
      effects: [{ source: 'a', operation: 'DAMAGE_REQUEST', arguments: ['b', 5, 'x'], accepted: false }, { source: 'a', operation: 'POWER_REQUEST', arguments: [1] }],
      entities: [{ ...batch.entities[0], type: 'rover', vmState: { mode: 'Patrol' } }],
    });
    expect(parsed.entities[0].type).toBe('other');
    expect(parsed.entities[0].vmState).toEqual({ mode: 'Patrol' });
    expect(parsed.effects).toEqual([
      { source: 'a', operation: 'DAMAGE_REQUEST', arguments: ['b', 5, 'x'], accepted: false },
      { source: 'a', operation: 'POWER_REQUEST', arguments: [1], accepted: true },
    ]);
    expect(parseTickBatch(batch).effects).toEqual([]);
    expect(parseTickBatch(batch).events).toEqual([]);
  });
  it('keeps world events with their causation and normalizes absent causes', () => {
    const parsed = parseTickBatch({
      ...batch,
      events: [
        { id: 'e1', type: 'DamageApplied', tick: 0, entityId: 'b', actorId: 'a', causationId: 'a@0#0', fields: { amount: 5 }, recipients: ['a'] },
        { id: 'e2', type: 'PowerLost', tick: 1, entityId: 'h', actorId: null, causationId: null, fields: {}, recipients: [] },
      ],
    });
    expect(parsed.events?.[0]).toMatchObject({ id: 'e1', actorId: 'a', causationId: 'a@0#0', recipients: ['a'] });
    expect(parsed.events?.[1].actorId).toBeUndefined();
    expect(parsed.events?.[1].causationId).toBeUndefined();
  });
  it('rejects malformed world events', () => {
    expect(() => parseTickBatch({ ...batch, events: 'x' })).toThrow();
    expect(() => parseTickBatch({ ...batch, events: [{ id: 'e', type: 'T', tick: 1.5, entityId: 'x', fields: {}, recipients: [] }] })).toThrow();
    expect(() => parseTickBatch({ ...batch, events: [{ id: 'e', type: 'T', tick: 1, entityId: 'x', fields: [], recipients: [] }] })).toThrow();
    expect(() => parseTickBatch({ ...batch, events: [{ id: 'e', type: 'T', tick: 1, entityId: 'x', fields: {}, recipients: [1] }] })).toThrow();
  });
  it('rejects malformed effects and VM state', () => {
    expect(() => parseTickBatch({ ...batch, effects: 'x' })).toThrow();
    expect(() => parseTickBatch({ ...batch, effects: [{ source: 1, operation: 'X', arguments: [] }] })).toThrow();
    expect(() => parseTickBatch({ ...batch, entities: [{ ...batch.entities[0], vmState: [] }] })).toThrow();
    expect(() => parseTickBatch({ ...batch, entities: [{ ...batch.entities[0], type: '' }] })).toThrow();
  });
  it('rejects invalid data before ingestion', () => {
    expect(() => parseTickBatch({ ...batch, version: 2 })).toThrow();
    expect(() => parseTickBatch({ ...batch, entities: [...batch.entities, ...batch.entities] })).toThrow();
    expect(() => parseTickBatch({ ...batch, entities: [{ ...batch.entities[0], metrics: { temperature: 'hot' } }] })).toThrow();
    expect(() => parseTickBatch({ ...batch, tickId: -1 })).toThrow();
  });
});

describe('run control', () => {
  const health = { version: 1, runtimeMode: 'reference', runId: 'r1', status: 'paused', tick: 4, ticks: 120, stepsPerSecond: 3 };

  it('accepts the gateway health document and rejects anything else', () => {
    expect(parseHealth(health)).toEqual({ status: 'paused', runId: 'r1', tick: 4, ticks: 120, stepsPerSecond: 3, error: undefined });
    expect(parseHealth({ ...health, status: 'failed', error: 'Division by zero' })?.error).toBe('Division by zero');
    expect(parseHealth({ ...health, status: 'exploded' })).toBeUndefined();
    expect(parseHealth({ ...health, tick: 'x' })).toBeUndefined();
    expect(parseHealth(null)).toBeUndefined();
    expect(parseHealth([])).toBeUndefined();
  });

  const source = (onHealthChange: (value: unknown) => void, onError: (message: string) => void): LiveBrokerSource =>
    new LiveBrokerSource({ healthUrl: '/health', streamUrl: '/stream', controlUrl: '/control', onHealthChange, onBatch: () => undefined, onError });

  it('posts commands to the control path and reports the new run state', async () => {
    const calls: Array<{ url: string; method?: string }> = [];
    vi.stubGlobal('fetch', async (url: string, init?: { method?: string }) => {
      calls.push({ url, method: init?.method });
      return { ok: true, status: 200, json: async () => health };
    });
    const seen: unknown[] = [];
    const live = source((value) => seen.push(value), () => undefined);
    expect((await live.control('step'))?.status).toBe('paused');
    await live.control({ speed: 2.5 });
    expect(calls).toEqual([{ url: '/control/step', method: 'POST' }, { url: '/control/speed?value=2.5', method: 'POST' }]);
    expect(seen).toHaveLength(2);
    vi.unstubAllGlobals();
  });

  it('surfaces a refused command instead of pretending it worked', async () => {
    vi.stubGlobal('fetch', async () => ({ ok: false, status: 400, json: async () => ({ error: 'speed must be between 0.1 and 100.0 steps per second' }) }));
    const errors: string[] = [];
    const live = source(() => undefined, (message) => errors.push(message));
    expect(await live.control({ speed: 0 })).toBeUndefined();
    expect(errors[0]).toContain('speed must be between');
    vi.stubGlobal('fetch', async () => { throw new Error('offline'); });
    expect(await live.control('pause')).toBeUndefined();
    expect(errors[1]).toContain('шлюз недоступен');
    vi.unstubAllGlobals();
  });
});
