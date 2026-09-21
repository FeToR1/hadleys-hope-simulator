import { describe, expect, it } from 'vitest';
import { MockDataGenerator } from './mockGenerator';
import { StateManager } from './stateManager';
import type { TickBatch } from './types';

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

describe('observed run log', () => {
  const entity = (status: 'nominal' | 'dead') => ({
    id: 'home-1/heater', pid: null, type: 'heater' as const, status, metrics: { health: status === 'dead' ? 0 : 100 },
    connectedTo: [], coordinates: { x: 0, y: 0 }, vmState: { demand: true },
  });
  const tick = (tickId: number, status: 'nominal' | 'dead', events: NonNullable<TickBatch['events']> = []): TickBatch => ({
    version: 1, runId: 'r1', runtimeMode: 'reference', seed: '426', full: true, tickId, timestamp: (tickId + 1) * 1000,
    entities: [entity(status)], events,
  });
  const attack = { id: 'e1', type: 'DamageApplied', tick: 1, entityId: 'home-1/heater', actorId: 'alien-1/xenomorph', causationId: 'alien-1/xenomorph@1#0',
    fields: { target: 'home-1/heater', amount: 100, reason: 'XenomorphAttack' }, recipients: [] };
  const broken = { id: 'e2', type: 'ObjectBroken', tick: 1, entityId: 'home-1/heater', causationId: 'e1',
    fields: { object: 'home-1/heater', reason: 'XenomorphAttack' }, recipients: [] };

  it('logs the events of a reference run once per tick and keeps them as history', () => {
    const manager = new StateManager();
    manager.ingest(tick(0, 'nominal'));
    expect(manager.snapshot().logs).toHaveLength(0);
    manager.ingest(tick(1, 'dead', [attack, broken]));
    manager.ingest(tick(1, 'dead', [attack, broken]));
    const messages = manager.snapshot().logs.map((log) => log.message);
    expect(messages).toHaveLength(2);
    expect(messages[0]).toContain('урон home-1/heater');
    expect(messages[1]).toContain('сломан home-1/heater');
    expect(manager.snapshot().events.map((item) => item.id)).toEqual(['e1', 'e2']);
  });

  it('bounds the event history and forgets it when the run changes', () => {
    const manager = new StateManager();
    manager.ingest(tick(0, 'nominal'));
    for (let index = 1; index <= 40; index += 1) {
      manager.ingest(tick(index, 'nominal', Array.from({ length: 20 }, (_, n) => ({ ...attack, id: `e${index}-${n}` }))));
    }
    expect(manager.snapshot().events).toHaveLength(500);
    expect(manager.snapshot().events.at(-1)?.id).toBe('e40-19');
    manager.ingest({ ...tick(0, 'nominal'), runId: 'r2' });
    expect(manager.snapshot().events).toHaveLength(0);
  });

  it('keeps the VM state of stored entities current and stays silent for mock data', () => {
    const manager = new StateManager();
    manager.ingest(tick(0, 'nominal'));
    manager.ingest({ ...tick(1, 'nominal'), entities: [{ ...entity('nominal'), vmState: { demand: false } }] });
    expect(manager.getEntity('home-1/heater')?.vmState).toEqual({ demand: false });
    const mock = new StateManager();
    mock.ingest(new MockDataGenerator().getInitialBatch());
    expect(mock.snapshot().logs).toHaveLength(0);
    expect(mock.snapshot().events).toHaveLength(0);
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
