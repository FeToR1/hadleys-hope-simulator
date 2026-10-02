import { createMapMarkers, type MapLayers, type MapMarker } from './mapPresentation';
import type { EntityState } from './types';

export interface MapBounds { x: number; y: number; width: number; height: number }
export interface SceneMarker extends MapMarker {
  key: string;
  clustered: boolean;
  position: { x: number; y: number };
}
const CELL = 140;
const severity = { nominal: 0, warning: 1, critical: 2, dead: 3 };

/** Built once per observed state. Camera movement queries nearby cells, not the whole city. */
export class MapSceneIndex {
  private readonly cells = new Map<string, MapMarker[]>();
  private readonly all: MapMarker[];
  constructor(entities: readonly EntityState[], layers: MapLayers, private readonly selectedId?: string) {
    this.all = createMapMarkers(entities, layers, selectedId);
    for (const marker of this.all) {
      const p = marker.entity.coordinates;
      const key = `${Math.floor(p.x / CELL)}:${Math.floor(p.y / CELL)}`;
      const bucket = this.cells.get(key);
      if (bucket) bucket.push(marker); else this.cells.set(key, [marker]);
    }
  }

  visible(bounds: MapBounds, scale: number): SceneMarker[] {
    const padding = 100 / Math.max(scale, 0.001);
    const left = bounds.x - padding; const right = bounds.x + bounds.width + padding;
    const top = bounds.y - padding; const bottom = bounds.y + bounds.height + padding;
    const minX = Math.floor(left / CELL); const maxX = Math.floor(right / CELL);
    const minY = Math.floor(top / CELL); const maxY = Math.floor(bottom / CELL);
    let candidates: MapMarker[];
    // A very distant view can cover many empty cells; scanning actual markers is cheaper there.
    if ((maxX - minX + 1) * (maxY - minY + 1) > this.cells.size * 2) candidates = this.all;
    else {
      candidates = [];
      for (let x = minX; x <= maxX; x++) for (let y = minY; y <= maxY; y++) {
        const bucket = this.cells.get(`${x}:${y}`);
        if (bucket) candidates.push(...bucket);
      }
    }
    const clusters = new Map<string, SceneMarker>();
    const visible: SceneMarker[] = [];
    // Quantized world cells avoid reshuffling groups during small camera/zoom changes.
    const size = CELL * 2 ** Math.max(0, Math.ceil(Math.log2(64 / (Math.max(scale, 0.001) * CELL))));
    for (const marker of candidates) {
      const p = marker.entity.coordinates;
      if (p.x < left || p.x > right || p.y < top || p.y > bottom) continue;
      const groupable = scale < 0.4 && ['house', 'civilian', 'power_node'].includes(marker.entity.type) && marker.entity.id !== this.selectedId;
      if (!groupable) {
        visible.push({ ...marker, key: `entity:${marker.entity.id}`, clustered: false, position: p });
        continue;
      }
      const key = `cluster:${marker.entity.type}:${size}:${Math.floor(p.x / size)}:${Math.floor(p.y / size)}`;
      const existing = clusters.get(key);
      if (existing) {
        const previous = existing.members.length; const added = marker.members.length;
        existing.position.x = (existing.position.x * previous + p.x * added) / (previous + added);
        existing.position.y = (existing.position.y * previous + p.y * added) / (previous + added);
        existing.members.push(...marker.members);
        if (severity[marker.entity.status] > severity[existing.entity.status]) existing.entity = marker.entity;
      } else clusters.set(key, { ...marker, members: [...marker.members], key, clustered: true, position: { ...p } });
    }
    for (const cluster of clusters.values()) visible.push(cluster);
    return visible;
  }
}
