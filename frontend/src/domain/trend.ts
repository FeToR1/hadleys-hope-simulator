import type { EntityState, TickBatch } from './types';

/** What the settlement looked like at one step, in the units the world uses. */
export interface TrendSample {
  tick: number;
  /** Power the grid actually handed out, in watts. */
  power: number;
  /** Mean temperature of the houses, in degrees Celsius. */
  temperature: number;
  /** Coldest house at this step, in degrees Celsius. */
  coldest: number;
  /** Houses with no water. */
  dryHouses: number;
  /** Everything the world has charged so far, in minimal money units. */
  spend: number;
}

/** How many steps the dashboard keeps; the full run stays in the JSONL output. */
export const TREND_WINDOW = 600;

const isHouse = (entity: EntityState): boolean => entity.type === 'house';

/**
 * One row of the trend from one snapshot. The dashboard only adds up what the world already decided:
 * granted power, house temperatures, houses without water, and the running total of the ledger.
 */
export function sampleTrend(batch: TickBatch, spendByOwner: ReadonlyMap<string, number>): TrendSample {
  let power = 0;
  let temperatureSum = 0;
  let houses = 0;
  let coldest = Number.POSITIVE_INFINITY;
  let dryHouses = 0;
  for (const entity of batch.entities) {
    power += entity.metrics.power_consumption ?? 0;
    if (!isHouse(entity)) continue;
    houses += 1;
    const temperature = entity.metrics.temperature;
    if (temperature !== undefined) {
      temperatureSum += temperature;
      if (temperature < coldest) coldest = temperature;
    }
    if (entity.metrics.water_level === 0) dryHouses += 1;
  }
  let spend = 0;
  for (const amount of spendByOwner.values()) spend += amount;
  return {
    tick: batch.tickId,
    power,
    temperature: houses > 0 ? temperatureSum / houses : 0,
    coldest: Number.isFinite(coldest) ? coldest : 0,
    dryHouses,
    spend,
  };
}

/** Appends a sample and keeps the window bounded, in place. */
export function pushTrend(trend: TrendSample[], sample: TrendSample): void {
  const last = trend[trend.length - 1];
  if (last !== undefined && last.tick >= sample.tick) return;
  trend.push(sample);
  if (trend.length > TREND_WINDOW) trend.splice(0, trend.length - TREND_WINDOW);
}
