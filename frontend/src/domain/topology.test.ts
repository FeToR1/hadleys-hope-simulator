import { describe, expect, it } from 'vitest';
import { createTopologyData } from './topology';
import type { EntityState } from './types';

const entity = (id: string, type: EntityState['type'], connectedTo: string[] = []): EntityState => ({
  id, pid: 1, type, status: 'nominal', metrics: {}, connectedTo, coordinates: { x: 0, y: 0 },
});

describe('createTopologyData', () => {
  it('filters non-infrastructure entities and creates unique directed edges', () => {
    const result = createTopologyData([
      entity('power-1', 'power_node', ['house-1', 'house-1', 'power-1']),
      entity('house-1', 'house', ['power-1']),
      entity('heater-1', 'heater', ['house-1']),
      entity('civilian-1', 'civilian', ['house-1']),
    ]);
    expect(result.nodes.map((node) => node.id)).toEqual(['power-1', 'house-1']);
    expect(result.edges).toEqual([{ id: 'power-1->house-1', source: 'power-1', target: 'house-1' }]);
  });
});
