import { describe, expect, it } from 'vitest';
import { ObserverDecoder } from './observerDecoder';
import { StateManager } from './stateManager';

const home = { id: 'home', pid: null, type: 'house', status: 'nominal', metrics: { temperature: 20 },
  coordinates: { x: 0, y: 0 }, connectedTo: [], parentId: null, vmState: { demand: false } };
const frame = (tickId: number, full: boolean, entities: unknown[], extra = {}) => ({ version: 2, runId: 'r',
  runtimeMode: 'reference', seed: '426', timestamp: tickId * 1000, tickId, full, baseTick: full ? null : tickId - 1,
  entities, removed: [], events: [], postings: [], ...extra });

describe('compact observer reconstruction', () => {
  it('replaces changed fields, retains unchanged entities and applies every posting exactly once', () => {
    const decoder = new ObserverDecoder(); const manager = new StateManager();
    manager.ingest(decoder.decode(frame(0, true, [home])));
    for (let tick = 1; tick <= 3; tick++) {
      const batch = decoder.decode(frame(tick, false, [{ id: 'home', metrics: { temperature: 20 - tick } }],
        { postings: [{ tick, owner: 'home', kind: 'electricity', amount: 10 }] }));
      manager.ingest(batch); manager.ingest(batch);
      expect(batch.entities[0].coordinates).toEqual({ x: 0, y: 0 });
      expect(batch.entities[0].vmState).toEqual({ demand: false });
    }
    expect(manager.snapshot().spendByOwner.get('home')).toBe(30);
    expect(manager.getEntity('home')?.metrics.temperature).toBe(17);
  });
  it('requires a valid baseline, handles gaps and accepts a new full run', () => {
    const decoder = new ObserverDecoder();
    expect(() => decoder.decode(frame(1, false, []))).toThrow();
    decoder.decode(frame(0, true, [home]));
    expect(() => decoder.decode(frame(2, false, []))).toThrow();
    decoder.reset();
    expect(() => decoder.decode(frame(1, false, []))).toThrow();
    expect(decoder.decode(frame(100, true, [home])).tickId).toBe(100);
    expect(decoder.decode(frame(0, true, [home], { runId: 'new' })).runId).toBe('new');
  });
  it('does not commit invalid patches and supports removal and complete additions', () => {
    const decoder = new ObserverDecoder(); decoder.decode(frame(0, true, [home]));
    expect(() => decoder.decode(frame(1, false, [{ id: 'home', coordinates: { x: Infinity, y: 0 } }]))).toThrow();
    expect(() => decoder.decode(frame(1, false, [{ id: 'home', metrics: { temperature: 2 } }, { id: 'home' }]))).toThrow();
    expect(() => decoder.decode(frame(1, false, [{ id: 'unknown', status: 'dead' }]))).toThrow();
    const added = { ...home, id: 'new-home' };
    expect(decoder.decode(frame(1, false, [added], { removed: ['home'] })).entities.map(entity => entity.id)).toEqual(['new-home']);
  });
});
