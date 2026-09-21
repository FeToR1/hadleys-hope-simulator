import { useEffect, useRef, useState, type JSX } from 'react';
import { Application, Container, Graphics, Text } from 'pixi.js';
import { Viewport } from 'pixi-viewport';
import type { EntityState } from '../domain/types';
import { NavigationControls } from './NavigationControls';

interface SettlementMapProps {
  entities: readonly EntityState[];
  onSelect: (entityId: string) => void;
  selectedId?: string;
  onRegisterFocus?: (focus: (entityId: string) => void) => void;
}

const colorForStatus = (status: EntityState['status']): number => ({
  nominal: 0x48d597,
  warning: 0xf2c94c,
  critical: 0xff8a4c,
  dead: 0xff4d67,
}[status]);

export function SettlementMap({ entities, onSelect, selectedId, onRegisterFocus }: SettlementMapProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement>(null);
  const entitiesRef = useRef(entities);
  const selectedIdRef = useRef(selectedId);
  const onSelectRef = useRef(onSelect);
  const updateStageRef = useRef<((currentEntities: readonly EntityState[]) => void) | null>(null);
  const viewportRef = useRef<Viewport | null>(null);
  const fitRef = useRef<(() => void) | null>(null);
  const objectsRef = useRef(new Map<string, Graphics>());
  const entityRef = useRef(new Map<string, EntityState>());
  const agentBadgesRef = useRef(new Map<string, Text>());
  const [viewportReady, setViewportReady] = useState(false);
  const [tooltip, setTooltip] = useState<{ entity: EntityState; x: number; y: number }>();
  entitiesRef.current = entities;
  selectedIdRef.current = selectedId;
  onSelectRef.current = onSelect;

  useEffect(() => {
    const host = hostRef.current;
    if (host === null) return;
    let disposed = false;
    let initialized = false;
    let resizeObserver: ResizeObserver | undefined;
    const application = new Application();
    const scene = new Container();
    application.init({ width: 900, height: 650, background: 0x0e1726, antialias: true }).then(() => {
      if (disposed) { application.destroy(true, { children: true }); return; }
      initialized = true;
      const viewport = new Viewport({ events: application.renderer.events, screenWidth: 900, screenHeight: 650, worldWidth: 1_500, worldHeight: 1_200 });
      viewportRef.current = viewport;
      setViewportReady(true);
      viewport.drag().pinch().wheel().decelerate();
      viewport.on('zoomed', () => updateStageRef.current?.(entitiesRef.current));
      host.appendChild(application.canvas);
      resizeObserver = new ResizeObserver(() => {
        const width = Math.max(1, host.clientWidth);
        const height = Math.max(1, host.clientHeight);
        application.renderer.resize(width, height);
        viewport.resize(width, height);
      });
      resizeObserver.observe(host);
      application.renderer.resize(Math.max(1, host.clientWidth), Math.max(1, host.clientHeight));
      viewport.resize(Math.max(1, host.clientWidth), Math.max(1, host.clientHeight));
      viewport.eventMode = 'static';
      application.stage.addChild(viewport);
      viewport.addChild(scene);

      const updateStage = (currentEntities: readonly EntityState[]): void => {
        entityRef.current.clear();
        const agentCounts = new Map<string, number>();
        for (const entity of currentEntities) {
          entityRef.current.set(entity.id, entity);
          if ((entity.type === 'civilian' || entity.type === 'xenomorph') && entity.parentId !== undefined) {
            agentCounts.set(entity.parentId, (agentCounts.get(entity.parentId) ?? 0) + 1);
          }
        }
        const clustered = viewport.scale.x < 0.8;
        for (const entity of currentEntities) {
          if (entity.type === 'heater' || entity.type === 'kettle') continue;
          let displayObject = objectsRef.current.get(entity.id);
          if (displayObject === undefined) {
            displayObject = new Graphics();
            displayObject.eventMode = 'static';
            displayObject.cursor = 'pointer';
            displayObject.on('pointertap', () => onSelectRef.current(entity.id));
            displayObject.on('pointerover', (event) => {
              const point = event.global;
              const current = entityRef.current.get(entity.id);
              if (current !== undefined) setTooltip({ entity: current, x: point.x, y: point.y });
            });
            displayObject.on('pointerout', () => setTooltip(undefined));
            objectsRef.current.set(entity.id, displayObject);
            scene.addChild(displayObject);
            if (entity.type === 'house') {
              const label = new Text({ text: entity.id.replace('house-', '#'), style: { fill: 0xb8c7dc, fontSize: 8 } });
              label.position.set(8, -5);
              displayObject.addChild(label);
              const badge = new Text({ text: '', style: { fill: 0xffffff, fontSize: 10, fontWeight: 'bold' } });
              badge.position.set(-8, 18);
              displayObject.addChild(badge);
              agentBadgesRef.current.set(entity.id, badge);
            }
          }
          const radius = entity.type === 'house' ? 16 : entity.type === 'power_node' ? 10 : 5;
          displayObject.clear().circle(0, 0, radius).fill({ color: colorForStatus(entity.status), alpha: entity.type === 'house' ? 0.55 : 0.9 });
          displayObject.position.set(entity.coordinates.x, entity.coordinates.y);
          const focused = selectedIdRef.current === undefined || entity.id === selectedIdRef.current ||
            entity.connectedTo.includes(selectedIdRef.current) ||
            entityRef.current.get(selectedIdRef.current ?? '')?.connectedTo.includes(entity.id);
          displayObject.alpha = entity.status === 'dead' ? 0.45 : focused ? 1 : 0.15;
          displayObject.visible = entity.type !== 'civilian' && entity.type !== 'xenomorph' || !clustered;
          if (entity.type === 'house') {
            const badge = agentBadgesRef.current.get(entity.id);
            if (badge !== undefined) {
              const count = agentCounts.get(entity.id) ?? 0;
              badge.text = clustered && count > 0 ? `👥 ${count}` : '';
              badge.visible = clustered;
            }
          }
        }
      };
      updateStage(entitiesRef.current);
      updateStageRef.current = updateStage;
      // Show what there is: the fixed 1500x1200 world suits 300 houses but hides a handful of objects in a corner.
      const fitToContent = (): void => {
        const list = entitiesRef.current;
        if (list.length === 0) return;
        let minX = Infinity; let minY = Infinity; let maxX = -Infinity; let maxY = -Infinity;
        for (const entity of list) {
          minX = Math.min(minX, entity.coordinates.x); maxX = Math.max(maxX, entity.coordinates.x);
          minY = Math.min(minY, entity.coordinates.y); maxY = Math.max(maxY, entity.coordinates.y);
        }
        const padding = 80;
        viewport.fit(false, Math.max(maxX - minX + 2 * padding, 240), Math.max(maxY - minY + 2 * padding, 240));
        viewport.moveCenter((minX + maxX) / 2, (minY + maxY) / 2);
        if (viewport.scale.x > 2) viewport.setZoom(2, true);
        updateStageRef.current?.(entitiesRef.current);
      };
      fitRef.current = fitToContent;
      fitToContent();
      onRegisterFocus?.((entityId) => {
        const entity = entityRef.current.get(entityId);
        if (entity !== undefined) {
          viewport.moveCenter(entity.coordinates.x, entity.coordinates.y);
          viewport.setZoom(1.5);
        }
      });
    }).catch(() => {
      if (!disposed) host.textContent = 'Не удалось инициализировать WebGL-сцену';
    });
    return () => {
      disposed = true;
      updateStageRef.current = null;
      fitRef.current = null;
      viewportRef.current = null;
      setViewportReady(false);
      objectsRef.current.clear();
      entityRef.current.clear();
      agentBadgesRef.current.clear();
      setTooltip(undefined);
      resizeObserver?.disconnect();
      if (initialized) application.destroy(true, { children: true });
      host.replaceChildren();
    };
  }, []);

  useEffect(() => {
    updateStageRef.current?.(entities);
  }, [entities, selectedId]);

  return (
    <div className="visualization-shell">
      <div ref={hostRef} className="map-host" aria-label="2D карта поселения" />
      {tooltip !== undefined && <EntityTooltip tooltip={tooltip} />}
      <NavigationControls
        disabled={!viewportReady}
        onZoomIn={() => viewportRef.current?.zoom(1.4)}
        onZoomOut={() => viewportRef.current?.zoom(0.7)}
        onReset={() => fitRef.current?.()}
      />
    </div>
  );
}

function EntityTooltip({ tooltip }: { tooltip: { entity: EntityState; x: number; y: number } }): JSX.Element {
  const { entity } = tooltip;
  return <div className="entity-tooltip" style={{ left: tooltip.x + 12, top: tooltip.y + 12 }}>
    <strong>{entity.id}</strong><span>{entity.type} · PID {entity.pid ?? '—'}</span>
    <span>Температура: {entity.metrics.temperature?.toFixed(1) ?? '—'} °C</span>
    <span>Вода: {entity.metrics.water_level?.toFixed(1) ?? '—'} %</span>
    <span>Мощность: {entity.metrics.power_consumption?.toFixed(1) ?? '—'} W</span>
  </div>;
}
