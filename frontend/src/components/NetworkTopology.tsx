import { useEffect, useRef, type JSX } from 'react';
import { Graph } from '@antv/g6';
import type { EntityState } from '../domain/types';

interface NetworkTopologyProps {
  entities: readonly EntityState[];
  onSelect: (entityId: string) => void;
}

const statusColor = (status: EntityState['status']): string => ({
  nominal: '#48d597',
  warning: '#f2c94c',
  critical: '#ff8a4c',
  dead: '#ff4d67',
}[status]);

export function NetworkTopology({ entities, onSelect }: NetworkTopologyProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const graphOperationRef = useRef<Promise<void>>(Promise.resolve());
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;
    const nodeEntities = entities.filter((entity) => entity.type === 'house' || entity.type === 'power_node');
    const edges = nodeEntities.flatMap((entity) =>
      entity.connectedTo
        .filter((target) => nodeEntities.some((candidate) => candidate.id === target))
        .map((target) => ({ id: `${entity.id}-${target}`, source: entity.id, target })),
    );
    const graph = new Graph({
      container: host,
      autoFit: 'view',
      padding: 24,
      data: {
        nodes: nodeEntities.map((entity) => ({
          id: entity.id,
          style: { fill: statusColor(entity.status), stroke: statusColor(entity.status), labelText: entity.id },
        })),
        edges: edges.map((edge) => ({ ...edge, style: { stroke: '#60718b', lineWidth: 1 } })),
      },
      node: { type: 'circle', style: { size: 12, labelFill: '#d8e4f3', labelFontSize: 8 } },
      edge: { type: 'line', style: { endArrow: true } },
      behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'],
    });
    graph.on('node:click', (event) => {
      const nodeId = String((event as { itemId?: string }).itemId ?? '');
      if (nodeId !== '') onSelectRef.current(nodeId);
    });
    let disposed = false;
    const initialRender = graph.render();
    graphOperationRef.current = initialRender;
    void initialRender.then(() => {
      if (!disposed) graphRef.current = graph;
    }).catch((error: unknown) => {
      if (!disposed) console.error('Не удалось отрисовать топологию сети', error);
    });
    return () => {
      disposed = true;
      graphRef.current = null;
      void graphOperationRef.current.then(() => graph.destroy()).catch((error: unknown) => {
        console.error('Не удалось корректно завершить топологию сети', error);
        graph.destroy();
      });
    };
  }, []);

  useEffect(() => {
    const graph = graphRef.current;
    if (graph === null) return;
    const nodeEntities = entities.filter((entity) => entity.type === 'house' || entity.type === 'power_node');
    graph.updateNodeData(nodeEntities.map((entity) => ({
      id: entity.id,
      style: { fill: statusColor(entity.status), stroke: statusColor(entity.status), opacity: entity.status === 'dead' ? 0.45 : 1 },
    })));
    const deadPowerNodes = new Set(nodeEntities.filter((entity) => entity.type === 'power_node' && entity.status === 'dead').map((entity) => entity.id));
    graph.updateEdgeData(nodeEntities.flatMap((entity) =>
      entity.connectedTo.filter((target) => nodeEntities.some((candidate) => candidate.id === target)).map((target) => ({
        id: `${entity.id}-${target}`,
        style: { stroke: deadPowerNodes.has(entity.id) ? '#ff4d67' : '#60718b', opacity: deadPowerNodes.has(entity.id) ? 0.35 : 1 },
      })),
    ));
    graphOperationRef.current = graphOperationRef.current.then(() => graph.draw());
    void graphOperationRef.current.catch((error: unknown) => {
      console.error('Не удалось обновить топологию сети', error);
    });
  }, [entities]);

  return <div ref={hostRef} className="topology-host" aria-label="Топология сетей" />;
}
