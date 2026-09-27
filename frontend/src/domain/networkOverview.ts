import type { EntityState } from './types';

export type NetworkKind = 'power' | 'water';
export interface HouseConnection {
  house: EntityState;
  path: EntityState[];
  problem: boolean;
  unknown: boolean;
}
export interface NetworkGroup {
  id: string;
  label: string;
  connections: HouseConnection[];
}
export interface NetworkOverview {
  equipment: EntityState[];
  groups: NetworkGroup[];
  houseCount: number;
  problemCount: number;
  unknownCount: number;
}

export const fixtureKind = (entity: EntityState): string =>
  typeof entity.vmState?.kind === 'string' ? entity.vmState.kind :
    entity.id.startsWith('water/pipe-') ? 'pipe' : entity.id === 'water/pump' ? 'pump' : '';

export function networkLabel(entity: EntityState): string {
  const kind = fixtureKind(entity);
  if (entity.id === 'grid/bus') return 'Распределительная шина';
  if (entity.id.startsWith('grid/pole-')) return `Линия ${entity.id.slice('grid/pole-'.length)}`;
  return ({ reactor: 'Реактор', solar: 'Солнечная станция', ups: 'ИБП', pump: 'Насос', pipe: 'Труба' } as Record<string, string>)[kind] ?? entity.id;
}

const idOrder = new Intl.Collator('en', { numeric: true });
const sortById = (a: EntityState, b: EntityState): number => idOrder.compare(a.id, b.id);
const faulty = (entity: EntityState): boolean => entity.status !== 'nominal';

/** Only use snapshot links. Grouping water pipes is navigation, not a new physical connection. */
export function createNetworkOverview(entities: readonly EntityState[], network: NetworkKind): NetworkOverview {
  const houses = entities.filter((entity) => entity.type === 'house').sort(sortById);
  const fixtures = entities.filter((entity) => entity.type === 'power_node' &&
    (network === 'power' ? fixtureKind(entity) !== 'pipe' : ['pipe', 'pump'].includes(fixtureKind(entity))));
  const byId = new Map([...houses, ...fixtures].map((entity) => [entity.id, entity]));
  const parents = new Map<string, EntityState[]>();
  for (const fixture of fixtures) {
    for (const target of new Set(fixture.connectedTo)) {
      if (target === fixture.id || !byId.has(target)) continue;
      const incoming = parents.get(target) ?? [];
      incoming.push(fixture);
      parents.set(target, incoming);
    }
  }
  const routes = new Map<string, EntityState[]>();
  function upstream(id: string, visiting = new Set<string>()): EntityState[] {
    if (visiting.has(id)) return [];
    const cached = routes.get(id);
    if (cached) return cached;
    const next = new Set(visiting).add(id);
    const route = new Map<string, EntityState>();
    for (const parent of parents.get(id) ?? []) {
      for (const ancestor of upstream(parent.id, next)) route.set(ancestor.id, ancestor);
      route.set(parent.id, parent);
    }
    const result = [...route.values()];
    routes.set(id, result);
    return result;
  }
  const connections = houses.map((house): HouseConnection => {
    const path = upstream(house.id);
    return {
      house, path,
      problem: network === 'water' ? house.metrics.water_level === 0 || path.some(faulty) : path.some(faulty),
      unknown: path.length === 0,
    };
  });
  const groups: NetworkGroup[] = [];
  if (network === 'power') {
    const grouped = new Map<string, NetworkGroup>();
    for (const connection of connections) {
      const feeds = parents.get(connection.house.id) ?? [];
      const id = feeds.map((feed) => feed.id).sort().join('|') || 'unconnected';
      let group = grouped.get(id);
      if (!group) {
        group = { id, label: feeds.map(networkLabel).join(' + ') || 'Без данных о подключении', connections: [] };
        grouped.set(id, group);
      }
      group.connections.push(connection);
    }
    groups.push(...[...grouped.values()].sort((a, b) => idOrder.compare(a.id, b.id)));
  } else {
    for (let start = 0; start < connections.length; start += 20) {
      const chunk = connections.slice(start, start + 20);
      groups.push({ id: `water:${chunk[0].house.id}`, label: `Дома ${start + 1}–${start + chunk.length}`, connections: chunk });
    }
  }
  const houseFeeders = new Set(houses.flatMap((house) => (parents.get(house.id) ?? []).map((feed) => feed.id)));
  return {
    equipment: fixtures.filter((fixture) => !houseFeeders.has(fixture.id)).sort(sortById),
    groups,
    houseCount: houses.length,
    problemCount: connections.filter((connection) => connection.problem).length,
    unknownCount: connections.filter((connection) => connection.unknown).length,
  };
}

export function connectionMatches(connection: HouseConnection, query: string): boolean {
  const search = query.trim().toLowerCase();
  return connection.house.id.toLowerCase().includes(search) || connection.path.some((node) =>
    node.id.toLowerCase().includes(search) || networkLabel(node).toLowerCase().includes(search));
}
