import type { EntityState } from './types';

export interface TopologyNode {
  id: string;
  type: EntityState['type'];
  status: EntityState['status'];
  label: string;
}

export interface TopologyEdge {
  id: string;
  source: string;
  target: string;
}

export interface TopologyData {
  nodes: TopologyNode[];
  edges: TopologyEdge[];
}

export function createTopologyData(entities: readonly EntityState[]): TopologyData {
  const visible = entities.filter((entity) => entity.type === 'house' || entity.type === 'power_node');
  const visibleIds = new Set(visible.map((entity) => entity.id));
  const nodes = visible.map((entity) => ({
    id: entity.id,
    type: entity.type,
    status: entity.status,
    label: entity.type === 'house' ? entity.id.replace('house-', '#') : entity.id,
  }));
  const edgeIds = new Set<string>();
  const edges: TopologyEdge[] = [];

  for (const entity of visible) {
    if (entity.type !== 'power_node') continue;
    for (const target of entity.connectedTo) {
      if (!visibleIds.has(target)) continue;
      const edgeId = `${entity.id}->${target}`;
      if (edgeIds.has(edgeId) || entity.id === target) continue;
      edgeIds.add(edgeId);
      edges.push({ id: edgeId, source: entity.id, target });
    }
  }
  return { nodes, edges };
}
