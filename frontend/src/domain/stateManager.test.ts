import { describe, expect, it } from 'vitest';
import { StateManager } from './stateManager';
import type { TickBatch } from './types';

const initial = (): TickBatch => ({
  runId: 'r1', runtimeMode: 'reference', seed: '426', full: true, tickId: 0, timestamp: 1000,
  entities: ['house-1', 'house-2'].map((id) => ({ id, pid: null, type: 'house', status: 'nominal',
    metrics: { temperature: 20 }, connectedTo: [], coordinates: { x: 0, y: 0 } })),
});

describe('StateManager', () => {
  it('keeps entity references stable while ingesting updates', () => {
    const manager = new StateManager();
    manager.ingest(initial());
    const before = manager.getEntity('house-1');
    expect(before).toBeDefined();
    manager.ingest({ ...initial(), tickId: 1, entities: [{ ...initial().entities[0], metrics: { temperature: 15 } }] });
    expect(manager.getEntity('house-1')).toBe(before);
    expect(before?.metrics.temperature).toBe(15);
  });

  it('retains a bounded log buffer', () => {
    const manager = new StateManager();
    for (let index = 0; index < 5_100; index += 1) {
      manager.appendLog({ timestamp: index, entityId: 'house-1', level: 'info', message: String(index) });
    }
    expect(manager.snapshot().logs).toHaveLength(200);
  });

  it('replaces data on a new run, ignores old ticks and removes absent entities', () => {
    const manager = new StateManager();
    manager.ingest(initial());
    manager.appendLog({ timestamp: 1, entityId: 'house-1', level: 'info', message: 'previous run' });
    const live = { ...initial(), runId: 'live-1', tickId: 10 };
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

  it('keeps creatine sales as income instead of charging them as spend', () => {
    const manager = new StateManager();
    manager.ingest({ ...initial(), postings: [
      { tick: 0, owner: 'home-1', kind: 'electricity', amount: 100 },
      { tick: 0, owner: 'colony', kind: 'creatine_sale', amount: 250 },
    ] });
    expect(manager.snapshot().spendByOwner.get('home-1')).toBe(100);
    expect(manager.snapshot().spendByOwner.has('colony')).toBe(false);
    expect(manager.snapshot().incomeByOwner?.get('colony')).toBe(250);
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

  it('adds the ledger up per owner and forgets it when the run changes', () => {
    const manager = new StateManager();
    const line = (owner: string, amount: number) => ({ tick: 1, owner, kind: 'electricity', amount });
    manager.ingest({ ...tick(0, 'nominal'), postings: [line('home-1/house', 100), line('settlement', 500)] });
    manager.ingest({ ...tick(1, 'nominal'), postings: [line('home-1/house', 40)] });
    expect(manager.snapshot().postings).toHaveLength(3);
    expect(manager.snapshot().spendByOwner.get('home-1/house')).toBe(140);
    expect(manager.snapshot().spendByOwner.get('settlement')).toBe(500);
    manager.ingest({ ...tick(0, 'nominal'), runId: 'r2' });
    expect(manager.snapshot().postings).toHaveLength(0);
    expect(manager.snapshot().spendByOwner.size).toBe(0);
  });

  it('keeps the VM state of stored entities current', () => {
    const manager = new StateManager();
    manager.ingest(tick(0, 'nominal'));
    manager.ingest({ ...tick(1, 'nominal'), entities: [{ ...entity('nominal'), vmState: { demand: false } }] });
    expect(manager.getEntity('home-1/heater')?.vmState).toEqual({ demand: false });
  });
});
