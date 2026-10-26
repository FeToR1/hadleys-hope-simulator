import type { EntityState } from './types';

export type EcologyMetric = 'forest_biomass' | 'manure' | 'plankton_biomass';
export type EcologyZoneLayer = 'forest' | 'manure' | 'plankton';
export const ECOLOGY_LAYER_METRICS: Record<EcologyZoneLayer, EcologyMetric> = {
  forest: 'forest_biomass', manure: 'manure', plankton: 'plankton_biomass',
};

export interface EcologyZoneSummary {
  zoneCount: number;
  pollutedZones: number;
  manureTotal: number;
  planktonTotal: number;
}

export function summarizeEcologyZones(entities: readonly EntityState[]): EcologyZoneSummary {
  let zoneCount = 0; let pollutedZones = 0; let manureTotal = 0; let planktonTotal = 0;
  for (const entity of entities) {
    if (entity.type !== 'ecology_zone') continue;
    zoneCount++;
    const manure = positiveFinite(entity.metrics.manure);
    const plankton = positiveFinite(entity.metrics.plankton_biomass);
    manureTotal += manure; planktonTotal += plankton;
    if (manure > 0 || plankton > 0) pollutedZones++;
  }
  return { zoneCount, pollutedZones, manureTotal, planktonTotal };
}

export interface EcologyZoneCell {
  entity: EntityState;
  x: number; y: number; width: number; height: number;
}
export interface EcologyViewBounds { x: number; y: number; width: number; height: number }

const MIN_INDEX_CELL = 280;
const positiveFinite = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;

/** Spatial index of the real zone extents. Camera pans query nearby cells without rescanning entities. */
export class EcologyZoneIndex {
  private readonly cells = new Map<string, EcologyZoneCell[]>();
  private readonly all: EcologyZoneCell[] = [];
  private readonly maxima: Record<EcologyMetric, number> = { forest_biomass: 0, manure: 0, plankton_biomass: 0 };
  private indexCell = MIN_INDEX_CELL;
  private fallbackAll = false;

  constructor(entities: readonly EntityState[]) {
    const valid = entities.flatMap((entity) => {
      if (entity.type !== 'ecology_zone') return [];
      const x = entity.metrics.min_x; const y = entity.metrics.min_y;
      const maxX = entity.metrics.max_x; const maxY = entity.metrics.max_y;
      if (![x, y, maxX, maxY].every((n) => typeof n === 'number' && Number.isFinite(n)) ||
        (maxX as number) <= (x as number) || (maxY as number) <= (y as number)) return [];
      const width = (maxX as number) - (x as number); const height = (maxY as number) - (y as number);
      if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return [];
      return [{ entity, x: x as number, y: y as number, width, height }];
    });
    this.indexCell = valid.reduce((largest, cell) => Math.max(largest, cell.width, cell.height), MIN_INDEX_CELL);
    for (const cell of valid) {
      this.all.push(cell);
      for (const metric of Object.keys(this.maxima) as EcologyMetric[]) {
        this.maxima[metric] = Math.max(this.maxima[metric], positiveFinite(cell.entity.metrics[metric]));
      }
      const minCellX = Math.floor(cell.x / this.indexCell); const maxCellX = Math.floor((cell.x + cell.width) / this.indexCell);
      const minCellY = Math.floor(cell.y / this.indexCell); const maxCellY = Math.floor((cell.y + cell.height) / this.indexCell);
      if (![minCellX, maxCellX, minCellY, maxCellY].every(Number.isSafeInteger)) { this.fallbackAll = true; continue; }
      for (let cx = minCellX; cx <= maxCellX; cx++) for (let cy = minCellY; cy <= maxCellY; cy++) {
        const key = `${cx}:${cy}`; const bucket = this.cells.get(key);
        if (bucket) bucket.push(cell); else this.cells.set(key, [cell]);
      }
    }
  }

  get size(): number { return this.all.length; }
  max(metric: EcologyMetric): number { return this.maxima[metric]; }

  /** Return the first enabled, biomass-bearing zone containing a world point.
   * Rectangles use half-open bounds so shared edges select exactly one zone.
   */
  at(point: { x: number; y: number }, layers: Readonly<Record<EcologyZoneLayer, boolean>>): EcologyZoneCell | undefined {
    if (!Number.isFinite(point.x) || !Number.isFinite(point.y)) return undefined;
    const candidates = this.visible({ x: point.x, y: point.y, width: 0, height: 0 })
      .filter((zone) => zone.x <= point.x && point.x < zone.x + zone.width &&
        zone.y <= point.y && point.y < zone.y + zone.height &&
        (Object.keys(ECOLOGY_LAYER_METRICS) as EcologyZoneLayer[]).some((layer) =>
          layers[layer] && positiveFinite(zone.entity.metrics[ECOLOGY_LAYER_METRICS[layer]]) > 0));
    return candidates.sort((a, b) => a.entity.id.localeCompare(b.entity.id))[0];
  }

  visible(bounds: EcologyViewBounds): EcologyZoneCell[] {
    if (![bounds.x, bounds.y, bounds.width, bounds.height].every(Number.isFinite) || bounds.width < 0 || bounds.height < 0) return [];
    const right = bounds.x + bounds.width; const bottom = bounds.y + bounds.height;
    if (!Number.isFinite(right) || !Number.isFinite(bottom)) return [];
    const intersects = (zone: EcologyZoneCell) => zone.x <= right && zone.x + zone.width >= bounds.x &&
      zone.y <= bottom && zone.y + zone.height >= bounds.y;
    const minX = Math.floor(bounds.x / this.indexCell); const maxX = Math.floor(right / this.indexCell);
    const minY = Math.floor(bounds.y / this.indexCell); const maxY = Math.floor(bottom / this.indexCell);
    const queryCells = (maxX - minX + 1) * (maxY - minY + 1);
    if (this.fallbackAll || ![minX, maxX, minY, maxY].every(Number.isSafeInteger) || !Number.isFinite(queryCells) ||
      queryCells > this.cells.size * 2 + 16) return this.all.filter(intersects);
    const candidates = new Set<EcologyZoneCell>();
    for (let x = minX; x <= maxX; x++) for (let y = minY; y <= maxY; y++) {
      for (const zone of this.cells.get(`${x}:${y}`) ?? []) candidates.add(zone);
    }
    return [...candidates].filter(intersects);
  }
}
