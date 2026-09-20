import { useEffect, useRef, useState, type JSX } from 'react';
import { Graph } from '@antv/g6';
import type { EntityState } from '../domain/types';
import { createTopologyData } from '../domain/topology';
import { NavigationControls } from './NavigationControls';

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
  const graphReadyRef = useRef(false);
  const onSelectRef = useRef(onSelect);
  const [graphReady, setGraphReady] = useState(false);
  onSelectRef.current = onSelect;

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;
    const topology = createTopologyData(entities);
    const graph = new Graph({
      container: host,
      autoFit: 'view',
      padding: 24,
      data: {
        nodes: topology.nodes.map((entity) => ({
          id: entity.id,
          style: { fill: statusColor(entity.status), stroke: statusColor(entity.status), labelText: entity.type === 'power_node' ? entity.label : '' },
        })),
        edges: topology.edges.map((edge) => ({ ...edge, style: { stroke: '#60718b', lineWidth: 1 } })),
      },
      node: { type: 'circle', style: { size: 12, labelFill: '#d8e4f3', labelFontSize: 8 } },
      edge: { type: 'line', style: { endArrow: true } },
      layout: { type: 'radial', unitRadius: 180, preventOverlap: true, nodeSize: 12, animation: false },
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
      if (!disposed) {
        graphRef.current = graph;
        graphReadyRef.current = true;
        setGraphReady(true);
      }
    }).catch((error: unknown) => {
      if (!disposed) console.error('Не удалось отрисовать топологию сети', error);
    });
    return () => {
      disposed = true;
      graphRef.current = null;
      graphReadyRef.current = false;
      setGraphReady(false);
      void graphOperationRef.current.then(() => graph.destroy()).catch((error: unknown) => {
        console.error('Не удалось корректно завершить топологию сети', error);
        graph.destroy();
      });
    };
  }, []);

  useEffect(() => {
    const graph = graphRef.current;
    if (graph === null) return;
    const topology = createTopologyData(entities);
    graph.setData({
      nodes: topology.nodes.map((entity) => ({
      id: entity.id,
      style: { fill: statusColor(entity.status), stroke: statusColor(entity.status), labelText: entity.type === 'power_node' ? entity.label : '', opacity: entity.status === 'dead' ? 0.45 : 1 },
    })),
      edges: topology.edges.map((edge) => {
        const source = topology.nodes.find((node) => node.id === edge.source);
        const isDead = source?.status === 'dead';
        return { ...edge, style: { stroke: isDead ? '#ff4d67' : '#60718b', opacity: isDead ? 0.35 : 1 } };
      }),
    });
    graphOperationRef.current = graphOperationRef.current.then(() => graph.draw());
    void graphOperationRef.current.catch((error: unknown) => {
      console.error('Не удалось обновить топологию сети', error);
    });
  }, [entities]);

  return (
    <div className="visualization-shell">
      <div ref={hostRef} className="topology-host" aria-label="Топология сетей" />
      <NavigationControls
        disabled={!graphReady}
        onZoomIn={() => void graphRef.current?.zoomTo(1.2)}
        onZoomOut={() => void graphRef.current?.zoomTo(0.8)}
        onReset={() => {
          const graph = graphRef.current;
          if (graph === null) return;
          graph.zoomTo(1);
          graph.fitView();
        }}
      />
    </div>
  );
}
