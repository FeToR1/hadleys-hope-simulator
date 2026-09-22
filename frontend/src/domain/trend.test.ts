import { describe, expect, it } from 'vitest';
import { TREND_WINDOW, pushTrend, sampleTrend, type TrendSample } from './trend';
import type { EntityState, TickBatch } from './types';

const house = (id: string, temperature: number, water: number, power = 0): EntityState => ({
  id, pid: null, type: 'house', status: 'nominal',
  metrics: { temperature, water_level: water, power_consumption: power },
  connectedTo: [], coordinates: { x: 0, y: 0 },
});

const batch = (entities: EntityState[], tickId = 5): TickBatch => ({ tickId, timestamp: 1000, entities });

describe('settlement trend', () => {
  it('averages the houses, finds the coldest and counts the ones without water', () => {
    const sample = sampleTrend(batch([house('a', 20, 100), house('b', -4, 0), house('c', 6, 0)]), new Map());
    expect(sample.tick).toBe(5);
    expect(sample.temperature).toBeCloseTo((20 - 4 + 6) / 3, 10);
    expect(sample.coldest).toBe(-4);
    expect(sample.dryHouses).toBe(2);
  });

  it('adds up granted power over everything, not only the houses', () => {
    const heater: EntityState = { id: 'h', pid: null, type: 'heater', status: 'nominal',
      metrics: { power_consumption: 8000 }, connectedTo: [], coordinates: { x: 0, y: 0 } };
    const pump: EntityState = { id: 'p', pid: null, type: 'power_node', status: 'nominal',
      metrics: { power_consumption: 40000 }, connectedTo: [], coordinates: { x: 0, y: 0 } };
    expect(sampleTrend(batch([house('a', 20, 100, 0), heater, pump]), new Map()).power).toBe(48000);
  });

  it('carries the running total of the ledger', () => {
    const spend = new Map([['home-1/house', 218], ['settlement', 145000]]);
    expect(sampleTrend(batch([house('a', 20, 100)]), spend).spend).toBe(145218);
  });

  it('stays sane without houses or without temperatures', () => {
    const sample = sampleTrend(batch([]), new Map());
    expect(sample.temperature).toBe(0);
    expect(sample.coldest).toBe(0);
    expect(sample.dryHouses).toBe(0);
  });

  it('keeps the window bounded and refuses a step it already has', () => {
    const trend: TrendSample[] = [];
    for (let tick = 0; tick < TREND_WINDOW + 50; tick += 1) {
      pushTrend(trend, sampleTrend(batch([house('a', 20, 100)], tick), new Map()));
    }
    expect(trend).toHaveLength(TREND_WINDOW);
    expect(trend[trend.length - 1].tick).toBe(TREND_WINDOW + 49);
    const before = trend.length;
    pushTrend(trend, sampleTrend(batch([house('a', 20, 100)], TREND_WINDOW + 49), new Map()));
    expect(trend).toHaveLength(before);
  });
});
