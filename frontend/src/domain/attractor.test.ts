import { describe, expect, it } from 'vitest';
import { classifyAttractor, createPhasePoint, phaseBounds, projectPhasePoint, serializeAttractorCsv } from './attractor';
import type { EntityState, PhasePoint } from './types';

const house = (temperature: number): EntityState => ({
  id: 'house-1', pid: 1, type: 'house', status: 'nominal',
  metrics: { temperature, power_consumption: 1_000 }, connectedTo: [], coordinates: { x: 0, y: 0 },
});

describe('attractor calculations', () => {
  it('calculates average temperature, power in kW, and ledger balance', () => {
    const point = createPhasePoint(3, [house(18), { ...house(22), id: 'house-2' }], -500);
    expect(point).toEqual({ tickId: 3, x: 20, y: 2, z: -500 });
  });

  it('keeps multi-megawatt states distinct before and after a reactor outage', () => {
    const before = { tickId: 10, x: 20, y: 6256, z: -500 };
    const after = { tickId: 11, x: 20, y: 4000, z: -500 };
    const bounds = phaseBounds([before, after]);
    const a = projectPhasePoint(before, bounds);
    const b = projectPhasePoint(after, bounds);
    expect(a.y).toBeGreaterThan(b.y);
    expect(a.y).toBeLessThan(1);
    expect(b.y).toBeGreaterThan(0);
    expect(a.y - b.y).toBeGreaterThan(0.3);
  });

  it('uses balance as the third coordinate and keeps a constant state fixed across time', () => {
    const point = { tickId: 10, x: 20, y: 5000, z: -500 };
    const changedBudget = { ...point, tickId: 11, z: -2000 };
    const bounds = phaseBounds([point, changedBudget]);
    expect(projectPhasePoint({ ...point, tickId: 10000 }, bounds)).toEqual(projectPhasePoint(point, bounds));
    const a = projectPhasePoint(point, bounds);
    const b = projectPhasePoint(changedBudget, bounds);
    expect(b.x).toBe(a.x); expect(b.y).toBe(a.y); expect(b.z).toBeLessThan(a.z);
    for (const p of [point, changedBudget]) {
      expect(Object.values(projectPhasePoint(p, bounds)).every((coordinate) => Number.isFinite(coordinate) && coordinate >= 0 && coordinate <= 1)).toBe(true);
    }
    expect(Object.values(projectPhasePoint({ tickId: 0, x: 0, y: 0, z: 0 }, phaseBounds([]))).every(Number.isFinite)).toBe(true);
  });

  it('distinguishes a single shock from periodic oscillations and a stable state', () => {
    const stable: PhasePoint[] = Array.from({ length: 10 }, (_, tickId) => ({ tickId, x: 20, y: 6256, z: -500 }));
    expect(classifyAttractor(stable)).toBe('stationary');
    expect(classifyAttractor([...stable, { tickId: 10, x: 20, y: 0, z: -500 }])).toBe('transient');
    expect(classifyAttractor([{ tickId: 0, x: 20, y: 0, z: 0 }])).toBe('stationary');
  });

  it('detects a collapse before other modes', () => {
    const history: PhasePoint[] = Array.from({ length: 10 }, (_, index) => ({ tickId: index, x: index === 9 ? -1 : 2, y: 2, z: -Math.pow(2, index) }));
    expect(classifyAttractor(history)).toBe('collapse');
  });

  it('serializes the required CSV header and rows', () => {
    expect(serializeAttractorCsv([{ tickId: 1, x: 20.5, y: 24, z: 100_000 }])).toBe(
      'TickID,AvgTemperature,TotalPower,GlobalBudget\n1,20.500000,24.000000,100000.000000',
    );
  });
});
