import type { EntityState, EntityType } from './types';

export const MAP_TYPES = {
  mine: { label: 'Шахта', singular: 'Шахта', color: '#d9b783', size: 88,
    shape: '<path d="M2 32L6 24L3 21L9 11L14 8L22 12L28 22L33 25L35 32Z" fill="#433b34" stroke="#7c6b54"/><path d="M7 31V18H22V31Z" fill="#1b2932" stroke="#d9b783" stroke-width="1.2"/><path d="M12 31V24Q12 19 17 19Q21 19 21 24V31" fill="#080f16"/><path d="M9 18L15 5H24L29 31M15 5L11 31M14 10H25M12 17H27M11 24H28M14 10L27 24M25 10L11 24" fill="none" stroke="#b3c5d0" stroke-width="1.2"/><circle cx="20" cy="5" r="3" fill="#384e5c" stroke="#d9b783"/><path d="M24 25H33L31 30H26Z" fill="#af8150" stroke="#d9b783"/><circle cx="26" cy="32" r="1.5" fill="#c9d6dc"/><circle cx="31" cy="32" r="1.5" fill="#c9d6dc"/><path d="M1 35H35" stroke="#d9b783" stroke-width="1.2"/>' },
  house: { label: 'Дома', singular: 'Дом', color: '#91b9e8', size: 36,
    shape: '<rect x="4" y="6" width="28" height="25" rx="3" fill="#1c344d" stroke="#91b9e8" stroke-width="1.5"/><path d="M4 13L18 4L32 13Z" fill="#456789" stroke="#91b9e8" stroke-width="1.5"/><path d="M9 18H14V23H9Z M22 18H27V23H22Z" fill="#c3deec"/><path d="M16 31V23H21V31" fill="#0b1928"/><path d="M8 34H29" stroke="#304760" stroke-width="2"/>' },
  civilian: { label: 'Жители', singular: 'Житель', color: '#e6d5b8', size: 19,
    shape: '<circle cx="18" cy="10" r="5" fill="#e6d5b8" stroke="#1b2731" stroke-width="2"/><path d="M9 29V23Q9 17 18 17Q27 17 27 23V29Z" fill="#e6d5b8" stroke="#1b2731" stroke-width="2"/><path d="M15 21V27M21 21V27" stroke="#93866e" stroke-width="2"/>' },
  rover: { label: 'Роверы', singular: 'Ровер', color: '#f4bc69', size: 29,
    shape: '<rect x="7" y="3" width="7" height="6" rx="2" fill="#0a101a" stroke="#ab824d"/><rect x="23" y="3" width="7" height="6" rx="2" fill="#0a101a" stroke="#ab824d"/><rect x="7" y="27" width="7" height="6" rx="2" fill="#0a101a" stroke="#ab824d"/><rect x="23" y="27" width="7" height="6" rx="2" fill="#0a101a" stroke="#ab824d"/><path d="M5 9H26L33 15V25L29 28H5Z" fill="#684b2d" stroke="#f4bc69" stroke-width="2"/><path d="M22 12H26L30 16V23H22Z" fill="#b7d5dc"/><path d="M10 13H17V23H10Z" fill="#bf8c48"/><path d="M33 17V21" stroke="#fff0c6" stroke-width="2"/>' },
  marine: { label: 'Морпехи', singular: 'Морпех', color: '#64d6c1', size: 23,
    shape: '<path d="M18 3L31 9V20Q27 29 18 34Q9 29 5 20V9Z" fill="#143d3e" stroke="#64d6c1" stroke-width="2"/><path d="M11 17V13Q11 7 18 7Q25 7 25 13V17Z" fill="#64d6c1"/><path d="M12 17H24V21H12Z" fill="#0c232c"/><path d="M13 25L18 29L23 25" fill="none" stroke="#a5eee0" stroke-width="2"/>' },
  xenomorph: { label: 'Ксеноморфы', singular: 'Ксеноморф', color: '#ee819e', size: 25,
    shape: '<path d="M18 2L34 18L18 34L2 18Z" fill="#341d32" stroke="#ee819e" stroke-width="1.5"/><path d="M11 19Q6 5 20 7Q29 7 25 18L21 24H15Z" fill="#c26083"/><path d="M12 14L17 17L24 13M15 22L18 25L21 22M24 22Q33 27 22 31" fill="none" stroke="#ffd2df" stroke-width="1.5"/>' },
  power_node: { label: 'Узлы сети', singular: 'Узел сети', color: '#48d597', size: 20,
    shape: '<circle cx="18" cy="18" r="12" fill="#48d597"/>' },
  fence: { label: 'Забор', singular: 'Секция забора', color: '#8ea3b9', size: 20,
    shape: '<path d="M3 13H33M3 25H33" stroke="#8ea3b9" stroke-width="2"/><path d="M7 6V31M18 6V31M29 6V31" stroke="#c4d2df" stroke-width="3"/>' },
  other: { label: 'Другие объекты', singular: 'Объект', color: '#a4aec2', size: 20,
    shape: '<rect x="7" y="7" width="22" height="22" rx="4" fill="#233249" stroke="#a4aec2" stroke-width="2"/><path d="M14 18H22M18 14V22" stroke="#a4aec2" stroke-width="2"/>' },
} as const;

export type MapKind = keyof typeof MAP_TYPES;
export const MOBILE_LAYERS = ['civilian', 'rover', 'marine', 'xenomorph'] as const;
export type MobileLayer = (typeof MOBILE_LAYERS)[number];
export type MapLayers = Record<MobileLayer, boolean>;
export const DEFAULT_MAP_LAYERS: MapLayers = { civilian: true, rover: true, marine: true, xenomorph: true };
export const MAP_STATUS = {
  nominal: { label: 'Норма', color: '#48d597', mark: '' },
  warning: { label: 'Внимание', color: '#f2c94c', mark: '!' },
  critical: { label: 'Критическое', color: '#ff8a4c', mark: '!' },
  dead: { label: 'Погиб / разрушен', color: '#ff617e', mark: '×' },
} as const;

export const mapKind = (type: EntityType): MapKind => type in MAP_TYPES ? type as MapKind : 'other';
export const mapIconSvg = (kind: MapKind): string => `<svg xmlns="http://www.w3.org/2000/svg" width="36" height="36" viewBox="0 0 36 36">${MAP_TYPES[kind].shape}</svg>`;
export const isMobile = (type: EntityType): type is MobileLayer => (MOBILE_LAYERS as readonly string[]).includes(type);
export const shortMapLabel = (entity: EntityState): string => {
  if (entity.type === 'mine') return 'ШАХТА';
  const index = entity.id.match(/(?:home|house|crew|transport|alien|marines|rover)-(\d+)/)?.[1];
  if (entity.type === 'house' && index) return `ДОМ ${index.padStart(2, '0')}`;
  if (entity.type === 'marine') return `М ${index ? `${index}·` : ''}${entity.id.match(/(?:marine-?)(\d+)$/)?.[1] ?? ''}`;
  const number = index ?? entity.id.match(/(\d+)$/)?.[1];
  if (entity.type === 'rover' && number) return `${entity.id.startsWith('crew-') ? 'РЕМ' : 'РОВЕР'} ${number}`;
  if (entity.type === 'civilian' && number) return `Ж ${number}`;
  if (entity.type === 'xenomorph' && number) return `К ${number}`;
  return entity.id;
};

export interface MapMarker { entity: EntityState; members: EntityState[] }
export interface FenceSegment { id: string; from: { x: number; y: number }; to: { x: number; y: number }; broken: boolean }

const point = (value: unknown): { x: number; y: number } | undefined => {
  const item = value as { x?: unknown; y?: unknown } | undefined;
  return typeof item?.x === 'number' && typeof item.y === 'number' ? { x: item.x, y: item.y } : undefined;
};

/** The fence is drawn as a line between the ends the world reports, not as a marker at its middle. */
export function fenceSegments(entities: readonly EntityState[]): FenceSegment[] {
  return entities.flatMap((entity) => {
    if (entity.type !== 'fence') return [];
    const from = point(entity.vmState?.from); const to = point(entity.vmState?.to);
    return from && to ? [{ id: entity.id, from, to, broken: entity.status === 'dead' }] : [];
  });
}

/** Group only co-located people, never hide a travelling resident at their home address. */
export function createMapMarkers(entities: readonly EntityState[], layers: MapLayers, selectedId?: string): MapMarker[] {
  const byId = new Map(entities.map((entity) => [entity.id, entity]));
  const mines = entities.filter((entity) => entity.type === 'mine');
  const workersAtMine = new Map<string, EntityState>();
  for (const mine of mines) for (const id of mine.connectedTo) workersAtMine.set(id, mine);
  const markers = new Map<string, MapMarker>();
  for (const entity of entities) {
    if (entity.type === 'heater' || entity.type === 'kettle' || entity.type === 'fence' || (isMobile(entity.type) && !layers[entity.type])) continue;
    // Workers are inside the mine; its counter represents them. A selected worker stays visible.
    const mine = workersAtMine.get(entity.id);
    if (entity.type === 'civilian' && entity.id !== selectedId && mine &&
      Math.hypot(mine.coordinates.x - entity.coordinates.x, mine.coordinates.y - entity.coordinates.y) < 1) continue;
    // A connection alone does not mean someone is aboard: the positions must also match.
    const vehicle = entity.connectedTo.map((id) => byId.get(id)).find((candidate) => candidate?.type === 'rover' &&
      Math.hypot(candidate.coordinates.x - entity.coordinates.x, candidate.coordinates.y - entity.coordinates.y) < 0.1);
    if (entity.type !== 'rover' && vehicle && layers.rover && entity.id !== selectedId) continue;
    const groupable = entity.type === 'civilian' || entity.type === 'marine' || entity.type === 'xenomorph';
    const key = groupable ? `${entity.type}:${Math.round(entity.coordinates.x * 2)}:${Math.round(entity.coordinates.y * 2)}` : entity.id;
    const existing = markers.get(key);
    if (existing) {
      existing.members.push(entity);
      const severity = { nominal: 0, warning: 1, critical: 2, dead: 3 };
      if (entity.id === selectedId || (existing.entity.id !== selectedId && severity[entity.status] > severity[existing.entity.status])) existing.entity = entity;
    } else markers.set(key, { entity, members: [entity] });
  }
  return [...markers.values()];
}
