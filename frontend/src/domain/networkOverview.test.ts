import { describe, expect, it } from 'vitest';
import { connectionMatches, createNetworkOverview } from './networkOverview';
import type { EntityState } from './types';

const entity = (id: string, type: EntityState['type'], connectedTo: string[] = [], kind?: string): EntityState => ({
  id, type, connectedTo, pid: null, status: 'nominal', coordinates: { x: 0, y: 0 },
  metrics: type === 'house' ? { water_level: 100 } : {}, vmState: kind ? { kind } : {},
});

export function largeNetwork(): EntityState[] {
  const nodes = [entity('grid/reactor', 'power_node', [], 'reactor'),
    entity('grid/bus', 'power_node', Array.from({ length: 15 }, (_, i) => `grid/pole-${i + 1}`), 'pole'),
    entity('water/pump', 'power_node', [], 'pump')];
  for (let i = 1; i <= 300; i++) {
    nodes.push(entity(`home-${i}/house`, 'house'), entity(`water/pipe-${i}`, 'power_node', [`home-${i}/house`], 'pipe'));
    nodes.push(entity(`home-${i}/resident`, 'civilian'), entity(`home-${i}/heater`, 'heater'));
  }
  for (let i = 0; i < 15; i++) nodes.push(entity(`grid/pole-${i + 1}`, 'power_node',
    Array.from({ length: 20 }, (_, j) => `home-${i * 20 + j + 1}/house`), 'pole'));
  return nodes;
}

describe('network overview', () => {
  it('reduces 300 houses to 15 lines and keeps water out of power routes', () => {
    const data = createNetworkOverview(largeNetwork(), 'power');
    expect(data.groups).toHaveLength(15);
    expect(data.groups.every((group) => group.connections.length === 20)).toBe(true);
    expect(data.groups[0].connections[0].path.map((node) => node.id)).toEqual(['grid/bus', 'grid/pole-1']);
    expect(data.houseCount).toBe(300);
    expect(data.unknownCount).toBe(0);
    expect(data.problemCount).toBe(0);
    expect(data.equipment.map((node) => node.id)).toContain('grid/reactor');
    // A missing reactor link must not be invented by the frontend.
    expect(data.groups[0].connections[0].path.some((node) => node.id === 'grid/reactor')).toBe(false);
  });

  it('propagates an upstream outage and updates after repair without counting houses twice', () => {
    const nodes = largeNetwork();
    const pole = nodes.find((node) => node.id === 'grid/pole-1')!;
    pole.connectedTo.push(pole.connectedTo[0]);
    pole.status = 'dead';
    expect(createNetworkOverview(nodes, 'power').problemCount).toBe(20);
    const bus = nodes.find((node) => node.id === 'grid/bus')!;
    bus.status = 'warning';
    expect(createNetworkOverview(nodes, 'power').problemCount).toBe(300);
    pole.status = 'nominal'; bus.status = 'nominal';
    expect(createNetworkOverview(nodes, 'power').problemCount).toBe(0);
  });

  it('groups pipes for browsing and uses actual water availability', () => {
    const nodes = largeNetwork();
    nodes.find((node) => node.id === 'home-27/house')!.metrics.water_level = 0;
    const data = createNetworkOverview(nodes, 'water');
    expect(data.groups).toHaveLength(15);
    expect(data.groups[1].connections[6].path.map((node) => node.id)).toEqual(['water/pipe-27']);
    expect(data.problemCount).toBe(1);
    expect(data.equipment.map((node) => node.id)).toEqual(['water/pump']);
    expect(connectionMatches(data.groups[1].connections[6], ' HOME-27 ')).toBe(true);
    expect(connectionMatches(data.groups[1].connections[6], 'pipe-27')).toBe(true);
  });

  it('handles absent connections, removals and cyclic malformed links without hanging', () => {
    const nodes = [entity('h', 'house'), entity('a', 'power_node', ['b', 'missing', 'a']), entity('b', 'power_node', ['a', 'h'])];
    expect(createNetworkOverview(nodes, 'power').groups[0].connections).toHaveLength(1);
    expect(createNetworkOverview([nodes[0]], 'power').unknownCount).toBe(1);
    expect(createNetworkOverview([], 'water').groups).toEqual([]);
  });
});
