import { describe, expect, it } from 'vitest';
import { MapSceneIndex } from './mapScene';
import { DEFAULT_MAP_LAYERS } from './mapPresentation';
import type { EntityState } from './types';

const house = (n: number): EntityState => ({ id: `home-${n}/house`, type: 'house', pid: null, status: 'nominal',
  metrics: {}, connectedTo: [], coordinates: { x: (n % 20) * 70, y: Math.floor(n / 20) * 70 } });
describe('map scene detail', () => {
  const city = Array.from({ length: 5000 }, (_, n) => house(n));
  it('groups a whole city at distant zoom without losing houses', () => {
    const visible = new MapSceneIndex(city, DEFAULT_MAP_LAYERS).visible({ x: -100, y: -100, width: 1800, height: 18000 }, 0.03);
    expect(visible.length).toBeLessThan(100);
    expect(visible.flatMap(marker => marker.members)).toHaveLength(5000);
    expect(visible.every(marker => marker.clustered)).toBe(true);
  });
  it('loads only visible individual houses nearby and changes them when panning', () => {
    const index = new MapSceneIndex(city, DEFAULT_MAP_LAYERS);
    const first = index.visible({ x: 0, y: 0, width: 500, height: 500 }, 1);
    const next = index.visible({ x: 0, y: 14000, width: 500, height: 500 }, 1);
    expect(first.length).toBeLessThan(120);
    expect(first.every(marker => !marker.clustered)).toBe(true);
    expect(next.length).toBeGreaterThan(0);
    expect(next.every(marker => marker.position.y > 13000)).toBe(true);
  });
  it('preserves selected houses and worst cluster status with negative coordinates', () => {
    const selected = { ...house(0), coordinates: { x: -10, y: -10 } };
    const damaged = { ...house(1), status: 'dead' as const, coordinates: { x: -20, y: -20 } };
    const intact = { ...house(2), coordinates: { x: -30, y: -30 } };
    const markers = new MapSceneIndex([selected, damaged, intact], DEFAULT_MAP_LAYERS, selected.id)
      .visible({ x: -100, y: -100, width: 200, height: 200 }, 0.1);
    expect(markers.find(marker => !marker.clustered)?.entity.id).toBe(selected.id);
    expect(markers.find(marker => marker.clustered)?.entity.status).toBe('dead');
    expect(markers.flatMap(marker => marker.members)).toHaveLength(3);
  });
});
