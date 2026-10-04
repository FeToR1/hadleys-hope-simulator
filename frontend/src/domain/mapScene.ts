import { createMapMarkers, type MapLayers, type MapMarker } from './mapPresentation';
import type { EntityState } from './types';

export interface MapBounds { x: number; y: number; width: number; height: number }
export interface SceneMarker extends MapMarker {
  key: string;
  clustered: boolean;
  position: { x: number; y: number };
  composition?: Partial<Record<EntityState['type'], number>>;
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
    const districts = new Map<string, SceneMarker>();
    const visible: SceneMarker[] = [];
    // House icons stay 16 screen pixels wide and their hit targets are 46 pixels
    // wide. Start grouping when the normal 70-world-pixel house spacing would
    // put those targets on top of one another. Power-of-two cells keep groups
    // stable as the camera moves and let the existing spatial hash cull work.
    const houseSpacingOnScreen = 70 * scale;
    const clusterableScale = houseSpacingOnScreen < 46;
    const size = 2 ** Math.ceil(Math.log2(48 / Math.max(scale, 0.001)));
    const overviewScale = scale < 0.15;
    const districtTypes = ['house', 'civilian', 'power_node', 'air_defense', 'ground_turret', 'rover'];
    for (const marker of candidates) {
      const p = marker.entity.coordinates;
      if (p.x < left || p.x > right || p.y < top || p.y > bottom) continue;
      if (overviewScale && districtTypes.includes(marker.entity.type) && marker.entity.id !== this.selectedId) {
        const key = `district:${size}:${Math.floor(p.x / size)}:${Math.floor(p.y / size)}`;
        const existing = districts.get(key);
        if (existing) {
          const previous = existing.members.length; const added = marker.members.length;
          existing.members.push(...marker.members);
          existing.position.x = (existing.position.x * previous + p.x * added) / (previous + added);
          existing.position.y = (existing.position.y * previous + p.y * added) / (previous + added);
          const hasHouse = existing.entity.type === 'house';
          const incomingHouse = marker.entity.type === 'house';
          if ((incomingHouse && !hasHouse) ||
            (incomingHouse === hasHouse && severity[marker.entity.status] > severity[existing.entity.status])) existing.entity = marker.entity;
        } else districts.set(key, { ...marker, key, clustered: true, members: [...marker.members],
          position: { ...p } });
        continue;
      }
      const groupable = clusterableScale && ['house', 'civilian', 'power_node'].includes(marker.entity.type) && marker.entity.id !== this.selectedId;
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
    for (const district of districts.values()) {
      const cellX = Math.floor(district.position.x / size); const cellY = Math.floor(district.position.y / size);
      if (district.members.length < 2) {
        visible.push({ ...district, key: `entity:${district.entity.id}`, clustered: false, position: district.entity.coordinates });
        continue;
      }
      district.position = { x: cellX * size + size / 2, y: cellY * size + size / 2 };
      district.composition = district.members.reduce<Partial<Record<EntityState['type'], number>>>((counts, member) => {
        counts[member.type] = (counts[member.type] ?? 0) + 1;
        return counts;
      }, {});
      visible.push(district);
    }
    for (const cluster of clusters.values()) {
      // A one-item cell remains an ordinary selectable marker; it should not
      // become a misleading cluster that zooms when clicked.
      if (cluster.members.length > 1) visible.push(cluster);
      else visible.push({ ...cluster, key: `entity:${cluster.entity.id}`, clustered: false, position: cluster.entity.coordinates });
    }
    return visible;
  }
}
