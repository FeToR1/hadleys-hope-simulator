import { describe, expect, it } from 'vitest';
import { parseTickBatch } from './dataSource';

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
  it('rejects invalid data before ingestion', () => {
    expect(() => parseTickBatch({ ...batch, version: 2 })).toThrow();
    expect(() => parseTickBatch({ ...batch, entities: [...batch.entities, ...batch.entities] })).toThrow();
    expect(() => parseTickBatch({ ...batch, entities: [{ ...batch.entities[0], metrics: { temperature: 'hot' } }] })).toThrow();
    expect(() => parseTickBatch({ ...batch, tickId: -1 })).toThrow();
  });
});
