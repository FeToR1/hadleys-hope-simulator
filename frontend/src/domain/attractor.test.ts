import { describe, expect, it } from 'vitest';
import { classifyAttractor, createPhasePoint, serializeAttractorCsv } from './attractor';
import type { EntityState, PhasePoint } from './types';

const house = (temperature: number): EntityState => ({
  id: 'house-1', pid: 1, type: 'house', status: 'nominal',
  metrics: { temperature, power_consumption: 1_000 }, connectedTo: [], coordinates: { x: 0, y: 0 },
});

describe('attractor calculations', () => {
  it('calculates average temperature, power in kW, and ledger balance', () => {
    const point = createPhasePoint(3, [house(18), { ...house(22), id: 'house-2' }], [{ tick: 3, owner: 'owner', kind: 'repair', amount: 500 }]);
    expect(point).toEqual({ tickId: 3, x: 20, y: 2, z: -500 });
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
