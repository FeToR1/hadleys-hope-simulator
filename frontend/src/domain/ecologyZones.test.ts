import { describe, expect, it } from 'vitest';
import { EcologyZoneIndex, summarizeEcologyZones } from './ecologyZones';
import type { EntityState } from './types';

const zone = (id: string, x: number, biomass: { forest?: number; manure?: number; plankton?: number }): EntityState => ({
  id, pid: null, type: 'ecology_zone', status: 'nominal', connectedTo: [], coordinates: { x: x + 5, y: 5 },
  metrics: { min_x: x, min_y: 0, max_x: x + 10, max_y: 10, forest_biomass: biomass.forest ?? 0,
    manure: biomass.manure ?? 0, plankton_biomass: biomass.plankton ?? 0 },
});

describe('ecology zone layers', () => {
  it('summarizes polluted zone count and total manure/plankton', () => {
    const summary = summarizeEcologyZones([
      zone('ecology/1', 0, { forest: 5, manure: 1.25, plankton: 0.5 }),
      zone('ecology/2', 10, { forest: 0, manure: 0.75, plankton: 0 }),
      zone('ecology/3', 20, { forest: 0, manure: 0, plankton: 0 }),
    ]);
    expect(summary).toEqual({ zoneCount: 3, pollutedZones: 2, manureTotal: 2, planktonTotal: 0.5 });
  });

  it('indexes real rectangle bounds and only returns zones crossing the viewport', () => {
    const index = new EcologyZoneIndex([
      zone('ecology/west', 0, { forest: 2 }),
      zone('ecology/east', 1000, { forest: 8 }),
    ]);
    expect(index.max('forest_biomass')).toBe(8);
    expect(index.visible({ x: 1005, y: 2, width: 2, height: 4 }).map((cell) => cell.entity.id)).toEqual(['ecology/east']);
    expect(index.visible({ x: 500, y: 0, width: 100, height: 10 })).toEqual([]);
  });

  it('selects only zones with biomass in an enabled layer and resolves shared edges consistently', () => {
    const index = new EcologyZoneIndex([
      zone('ecology/forest', 0, { forest: 2 }),
      zone('ecology/manure', 10, { manure: 3 }),
      zone('ecology/empty', 20, {}),
    ]);
    expect(index.at({ x: 5, y: 5 }, { forest: false, manure: true, plankton: false })).toBeUndefined();
    expect(index.at({ x: 5, y: 5 }, { forest: true, manure: false, plankton: false })?.entity.id).toBe('ecology/forest');
    expect(index.at({ x: 10, y: 5 }, { forest: true, manure: true, plankton: false })?.entity.id).toBe('ecology/manure');
    expect(index.at({ x: 25, y: 5 }, { forest: true, manure: true, plankton: true })).toBeUndefined();
  });

  it('bounds indexing work for giant zones and giant viewports', () => {
    const giant = zone('ecology/giant', 0, { forest: 4 });
    giant.metrics.max_x = 1_000_000_000;
    giant.metrics.max_y = 1_000_000_000;
    const index = new EcologyZoneIndex([giant]);
    expect(index.visible({ x: -1_000_000_000_000, y: -1_000_000_000_000,
      width: 2_000_000_000_000, height: 2_000_000_000_000 })).toHaveLength(1);
    expect(index.visible({ x: 2_000_000_000, y: 2_000_000_000, width: 1, height: 1 })).toEqual([]);
  });
});
