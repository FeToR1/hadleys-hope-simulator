import { useEffect, useRef, useState, type JSX } from 'react';
import { Graph } from '@antv/g6';
import type { EntityState } from '../domain/types';
import { createTopologyData } from '../domain/topology';
import { NavigationControls } from './NavigationControls';

interface NetworkTopologyProps {
  entities: readonly EntityState[];
  onSelect: (entityId: string) => void;
  selectedId?: string;
  onRegisterFocus?: (focus: (entityId: string) => void) => void;
}

const statusColor = (status: EntityState['status']): string => ({
  nominal: '#48d597',
  warning: '#f2c94c',
  critical: '#ff8a4c',
  dead: '#ff4d67',
}[status]);

export function NetworkTopology({ entities, onSelect, selectedId, onRegisterFocus }: NetworkTopologyProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const graphOperationRef = useRef<Promise<void>>(Promise.resolve());
  const graphReadyRef = useRef(false);
  const manifestReadyRef = useRef(false);
  const onSelectRef = useRef(onSelect);
  const entitiesRef = useRef(entities);
  const [graphReady, setGraphReady] = useState(false);
  onSelectRef.current = onSelect;
  entitiesRef.current = entities;

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
      plugins: [{
        type: 'tooltip',
        trigger: 'hover',
        getContent: (_event: unknown, items: Array<{ id?: string }>) => {
          const item = items[0];
          const entity = entitiesRef.current.find((candidate) => candidate.id === String(item?.id));
          if (entity === undefined) return '';
          return `<div class="g6-tooltip-content"><strong>${entity.id}</strong><span>${entity.type} · PID ${entity.pid ?? '—'}</span><span>Температура: ${entity.metrics.temperature?.toFixed(1) ?? '—'} °C</span><span>Вода: ${entity.metrics.water_level?.toFixed(1) ?? '—'} %</span><span>Мощность: ${entity.metrics.power_consumption?.toFixed(1) ?? '—'} W</span></div>`;
        },
        onOpenChange: () => undefined,
      }],
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
        manifestReadyRef.current = topology.nodes.length > 0;
        setGraphReady(true);
        onRegisterFocus?.((entityId) => {
          void graph.focusElement(entityId, true);
        });
      }
    }).catch((error: unknown) => {
      if (!disposed) console.error('Не удалось отрисовать топологию сети', error);
    });
    return () => {
      disposed = true;
      graphRef.current = null;
      graphReadyRef.current = false;
      manifestReadyRef.current = false;
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
    const selectedEntity = topology.nodes.find((node) => node.id === selectedId);
    const connectedIds = new Set<string>(selectedId === undefined ? topology.nodes.map((node) => node.id) : [selectedId]);
    if (selectedEntity !== undefined) {
      for (const edge of topology.edges) {
        if (edge.source === selectedId || edge.target === selectedId) {
          connectedIds.add(edge.source);
          connectedIds.add(edge.target);
        }
      }
    }
    if (!manifestReadyRef.current) {
      graph.setData({
        nodes: topology.nodes.map((entity) => ({ id: entity.id, style: { fill: statusColor(entity.status), stroke: statusColor(entity.status), labelText: entity.type === 'power_node' ? entity.label : '' } })),
        edges: topology.edges.map((edge) => ({ ...edge, style: { stroke: '#60718b', lineWidth: 1 } })),
      });
      manifestReadyRef.current = topology.nodes.length > 0;
    }
    graph.updateNodeData(topology.nodes.map((entity) => ({
      id: entity.id,
      style: { fill: statusColor(entity.status), stroke: connectedIds.has(entity.id) ? '#6fffc0' : statusColor(entity.status), labelText: entity.type === 'power_node' ? entity.label : '', opacity: connectedIds.has(entity.id) ? 1 : 0.15 },
    })));
    graph.updateEdgeData(topology.edges.map((edge) => {
      const source = topology.nodes.find((node) => node.id === edge.source);
      const isDead = source?.status === 'dead';
      const highlighted = selectedId === undefined || edge.source === selectedId || edge.target === selectedId;
      return { ...edge, style: { stroke: isDead ? '#ff4d67' : highlighted ? '#6fffc0' : '#60718b', lineWidth: highlighted ? 3 : 1, opacity: isDead ? 0.35 : highlighted ? 1 : 0.15 } };
    }));
    graphOperationRef.current = graphOperationRef.current.then(() => graph.draw());
    void graphOperationRef.current.catch((error: unknown) => {
      console.error('Не удалось обновить топологию сети', error);
    });
  }, [entities, selectedId]);

  return (
    <div className="visualization-shell">
      <div ref={hostRef} className="topology-host" aria-label="Топология сетей" />
      <NavigationControls
        disabled={!graphReady}
        onZoomIn={() => {
          const graph = graphRef.current;
          if (graph !== null) void graph.zoomTo(graph.getZoom() * 1.4);
        }}
        onZoomOut={() => {
          const graph = graphRef.current;
          if (graph !== null) void graph.zoomTo(graph.getZoom() * 0.7);
        }}
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
