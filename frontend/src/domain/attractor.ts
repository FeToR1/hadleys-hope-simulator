import type { AttractorMode, EntityState, PhasePoint, WorldEvent } from './types';

export const MAX_ATTRACTOR_HISTORY = 10_000;

export function createPhasePoint(tickId: number, entities: readonly EntityState[], budget: number, events: readonly WorldEvent[] = []): PhasePoint {
  const houses = entities.filter((entity) => entity.type === 'house' && entity.metrics.temperature !== undefined);
  const temperatureTotal = houses.reduce((sum, entity) => sum + (entity.metrics.temperature ?? 0), 0);
  const powerWatts = entities.reduce((sum, entity) => sum + (entity.metrics.power_consumption ?? 0), 0);
  return {
    tickId,
    x: houses.length === 0 ? 0 : temperatureTotal / houses.length,
    y: powerWatts / 1000,
    z: budget,
    ...(events.some((event) => event.type === 'ReactorExploded') ? { reactorExplosion: true } : {}),
  };
}

export interface PhaseBounds {
  x: readonly [number, number];
  y: readonly [number, number];
  z: readonly [number, number];
}

/** Shared physical axes for the whole displayed trail, with room for constant or empty series. */
export function phaseBounds(history: readonly PhasePoint[]): PhaseBounds {
  let minX = Infinity; let maxX = -Infinity;
  let maxY = 0; let minZ = 0; let maxZ = 0;
  for (const point of history) {
    minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
    maxY = Math.max(maxY, point.y);
    minZ = Math.min(minZ, point.z); maxZ = Math.max(maxZ, point.z);
  }
  const padded = (min: number, max: number, minSpan: number): readonly [number, number] => {
    const center = (min + max) / 2;
    const half = Math.max(minSpan, max - min) * 0.6;
    return [center - half, center + half];
  };
  return {
    x: history.length === 0 ? [-1, 1] : padded(minX, maxX, 1),
    y: [0, Math.max(100, maxY) * 1.08],
    z: padded(minZ, maxZ, 1),
  };
}

/** Scale actual temperature, power and balance; tick IDs never affect spatial coordinates. */
export function projectPhasePoint(point: PhasePoint, bounds: PhaseBounds): { x: number; y: number; z: number } {
  const scale = (value: number, range: readonly [number, number]): number => (value - range[0]) / (range[1] - range[0]);
  return { x: scale(point.x, bounds.x), y: scale(point.y, bounds.y), z: scale(point.z, bounds.z) };
}

export function classifyAttractor(history: readonly PhasePoint[], scenario?: string): AttractorMode {
  if (history.length < 2) return 'stationary';
  const latest = history.at(-1)!;
  const window = history.slice(-50);
  if (latest.x < 0 && hasExponentialLosses(window)) return 'collapse';
  if (window.length >= 10 && isStationary(window)) return 'stationary';
  if (window.length >= 12 && isPeriodic(window)) return 'periodic';
  if ((scenario === 'storm' || scenario === 'xenomorph') && hasChaoticJumps(window)) return 'chaotic';
  return hasChaoticJumps(window) ? 'chaotic' : 'transient';
}

function isStationary(points: readonly PhasePoint[]): boolean {
  const temperatures = points.map((point) => point.x);
  const powers = points.map((point) => point.y);
  return relativeRange(temperatures) <= 0.01 && relativeRange(powers) <= 0.01 && linearResidual(points.map((point) => point.z)) <= 0.02;
}

function isPeriodic(points: readonly PhasePoint[]): boolean {
  const values = points.map((point) => point.x);
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length;
  const crossings = values.slice(1).filter((value, index) => (value - mean) * (values[index] - mean) < 0).length;
  return crossings >= 2 && relativeRange(values) > 0.01 && relativeRange(values) < 0.35;
}

function hasChaoticJumps(points: readonly PhasePoint[]): boolean {
  if (points.length < 4) return false;
  let jumps = 0;
  for (let index = 1; index < points.length; index += 1) {
    const previous = points[index - 1];
    const current = points[index];
    if (Math.abs(current.x - previous.x) > 1 || Math.abs(current.y - previous.y) > 0.1) jumps += 1;
  }
  return jumps >= Math.max(2, Math.floor(points.length * 0.15));
}

function hasExponentialLosses(points: readonly PhasePoint[]): boolean {
  if (points.length < 4) return false;
  const losses = points.map((point) => Math.max(0, -point.z));
  return losses.at(-1)! > Math.max(1, losses[0]) * 2 && losses.at(-1)! > losses[Math.floor(losses.length / 2)] * 1.3;
}

function relativeRange(values: readonly number[]): number {
  const mean = Math.abs(values.reduce((sum, value) => sum + value, 0) / values.length);
  if (mean === 0) return 0;
  return (Math.max(...values) - Math.min(...values)) / mean;
}

function linearResidual(values: readonly number[]): number {
  if (values.length < 2) return 0;
  const first = values[0];
  const last = values.at(-1)!;
  const slope = (last - first) / (values.length - 1);
  const scale = Math.max(1, Math.abs(first), Math.abs(last));
  return Math.max(...values.map((value, index) => Math.abs(value - (first + slope * index)))) / scale;
}

export function serializeAttractorCsv(history: readonly PhasePoint[]): string {
  const rows = [['TickID', 'AvgTemperature', 'TotalPower', 'GlobalBudget']];
  for (const point of history) {
    rows.push([String(point.tickId), point.x.toFixed(6), point.y.toFixed(6), point.z.toFixed(6)]);
  }
  return rows.map((row) => row.join(',')).join('\n');
}
