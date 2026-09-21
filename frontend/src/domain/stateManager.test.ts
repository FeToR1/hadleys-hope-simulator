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
