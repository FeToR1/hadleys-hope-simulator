import { describe, expect, it } from 'vitest';
import { createMapMarkers, DEFAULT_MAP_LAYERS, fenceSegments } from './mapPresentation';
import type { EntityState } from './types';

const entity = (id: string, type: EntityState['type'], extra: Partial<EntityState> = {}): EntityState => ({
  id, type, pid: null, status: 'nominal', metrics: {}, connectedTo: [], coordinates: { x: 0, y: 0 }, ...extra,
});

describe('map markers', () => {
  it('shows a mine in place of workers inside it, but keeps arrivals and selected workers visible', () => {
    const worker = entity('worker', 'civilian', { vmState: { activity: 'Mining' } });
    const walking = entity('walking', 'civilian', { coordinates: { x: 10, y: 0 } });
    const mine = entity('site/mine', 'mine', { metrics: { workers: 1 }, connectedTo: ['worker'] });
    const input = [mine, worker, walking];
    expect(createMapMarkers(input, DEFAULT_MAP_LAYERS).map(({ entity }) => entity.id)).toEqual(['site/mine', 'walking']);
    expect(createMapMarkers(input, DEFAULT_MAP_LAYERS, 'worker').map(({ entity }) => entity.id)).toContain('worker');
  });
  it('keeps a travelling resident at their actual position and never hides threats with distance', () => {
    const house = entity('home', 'house');
    const resident = entity('person', 'civilian', { parentId: 'home', coordinates: { x: 120, y: 20 } });
    const alien = entity('alien', 'xenomorph', { coordinates: { x: -200, y: 0 } });
    const markers = createMapMarkers([house, resident, alien], DEFAULT_MAP_LAYERS);
    expect(markers.map(({ entity }) => entity)).toEqual([house, resident, alien]);
  });

  it('only hides a rider when their visible rover is physically co-located', () => {
    const rover = entity('rover', 'rover');
    const rider = entity('rider', 'marine', { connectedTo: ['rover'] });
    const approaching = entity('walking', 'marine', { connectedTo: ['rover'], coordinates: { x: 5, y: 0 } });
    const input = [rover, rider, approaching];
    expect(createMapMarkers(input, DEFAULT_MAP_LAYERS).map(({ entity }) => entity.id)).toEqual(['rover', 'walking']);
    expect(createMapMarkers(input, { ...DEFAULT_MAP_LAYERS, rover: false }).flatMap(({ members }) => members)).toEqual([rider, approaching]);
    expect(createMapMarkers(input, DEFAULT_MAP_LAYERS, 'rider').map(({ entity }) => entity.id)).toContain('rider');
  });

  it('keeps every member of a co-located group, represents its worst status and preserves selection', () => {
    const group = [entity('a', 'marine'), entity('b', 'marine', { status: 'warning' }), entity('c', 'marine', { status: 'dead' })];
    const [marker] = createMapMarkers(group, DEFAULT_MAP_LAYERS);
    expect(marker.members).toHaveLength(3);
    expect(marker.entity.id).toBe('c');
    expect(createMapMarkers(group, DEFAULT_MAP_LAYERS, 'a')[0].entity.id).toBe('a');
  });

  it('filters mobile layers independently and keeps network fixtures intact', () => {
    const input = [entity('house', 'house'), entity('person', 'civilian'), entity('alien', 'xenomorph'),
      entity('rover', 'rover'), entity('marine', 'marine'), entity('pipe', 'power_node'), entity('heater', 'heater')];
    const markers = createMapMarkers(input, { ...DEFAULT_MAP_LAYERS, civilian: false, marine: false });
    expect(markers.map(({ entity }) => entity.id)).toEqual(['house', 'alien', 'rover', 'pipe']);
  });

  it('keeps ecology zones out of point markers so their area overlays do not cover buildings', () => {
    const zone = entity('ecology/forest/1', 'ecology_zone', { coordinates: { x: 0, y: 0 }, metrics: { forest_biomass: 12 } });
    const house = entity('home-1/house', 'house');
    expect(createMapMarkers([zone, house], DEFAULT_MAP_LAYERS).map(({ entity: item }) => item.id)).toEqual([house.id]);
  });

  it('draws the fence between the ends the world reports and never as markers', () => {
    const intact = entity('fence/1', 'fence', { vmState: { kind: 'fence', from: { x: 0, y: 0 }, to: { x: 60, y: 0 } } });
    const breach = entity('fence/2', 'fence', { status: 'dead', vmState: { kind: 'fence', from: { x: 60, y: 0 }, to: { x: 120, y: 0 } } });
    const malformed = entity('fence/3', 'fence', { vmState: { kind: 'fence' } });
    expect(fenceSegments([intact, breach, malformed])).toEqual([
      { id: 'fence/1', from: { x: 0, y: 0 }, to: { x: 60, y: 0 }, broken: false },
      { id: 'fence/2', from: { x: 60, y: 0 }, to: { x: 120, y: 0 }, broken: true },
    ]);
    expect(createMapMarkers([intact, breach], DEFAULT_MAP_LAYERS)).toEqual([]);
  });
});
