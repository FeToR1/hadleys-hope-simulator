import { describe, expect, it } from 'vitest';
import { MockDataGenerator } from './mockGenerator';
import { StateManager } from './stateManager';

describe('StateManager', () => {
  it('keeps entity references stable while ingesting updates', () => {
    const manager = new StateManager();
    const generator = new MockDataGenerator(() => 0.5);
    manager.ingest(generator.getInitialBatch());
    const before = manager.getEntity('house-1');
    expect(before).toBeDefined();
    manager.ingest({ ...generator.step(), entities: [generator.getInitialBatch().entities[0]] });
    expect(manager.getEntity('house-1')).toBe(before);
  });

  it('retains a bounded log buffer', () => {
    const manager = new StateManager();
    for (let index = 0; index < 5_100; index += 1) {
      manager.appendLog({ timestamp: index, entityId: 'house-1', level: 'info', message: String(index) });
    }
    expect(manager.snapshot().logs).toHaveLength(200);
  });

  it('replaces mock data on live run, ignores old ticks and removes absent entities', () => {
    const manager = new StateManager();
    const mock = new MockDataGenerator().getInitialBatch();
    manager.ingest(mock);
    manager.appendLog({ timestamp: 1, entityId: 'house-1', level: 'info', message: 'mock' });
    const live = { ...mock, runId: 'live-1', full: true, tickId: 10, seed: '426', runtimeMode: 'reference' as const,
      entities: mock.entities.slice(0, 2) };
    manager.ingest(live);
    expect(manager.snapshot().entities.size).toBe(2);
    expect(manager.snapshot().logs).toHaveLength(0);
    manager.ingest({ ...live, tickId: 9, entities: [] });
    expect(manager.snapshot().entities.size).toBe(2);
    manager.ingest({ ...live, tickId: 11, entities: [] });
    expect(manager.snapshot().entities.size).toBe(0);
    manager.ingest({ ...live, runId: 'live-2', tickId: 0 });
    expect(manager.snapshot().entities.size).toBe(2);
    expect(manager.snapshot().tickId).toBe(0);
  });
});

describe('MockDataGenerator', () => {
  it('creates the requested settlement scale and a deterministic failure', () => {
    const generator = new MockDataGenerator(() => 0);
    const initial = generator.getInitialBatch();
    expect(initial.entities.filter((entity) => entity.type === 'house')).toHaveLength(300);
    expect(initial.entities.some((entity) => entity.type === 'xenomorph')).toBe(true);
    let failed = false;
    for (let index = 0; index < 60; index += 1) {
      failed = generator.step().entities.some((entity) => entity.id === 'power-1' && entity.status === 'dead');
      if (failed) break;
    }
    expect(failed).toBe(true);
  });
});
