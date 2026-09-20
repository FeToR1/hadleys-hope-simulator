import { useEffect, useRef, useState, type JSX } from 'react';
import { Application, Container, Graphics, Text } from 'pixi.js';
import { Viewport } from 'pixi-viewport';
import type { EntityState } from '../domain/types';
import { NavigationControls } from './NavigationControls';

interface SettlementMapProps {
  entities: readonly EntityState[];
  onSelect: (entityId: string) => void;
}

const colorForStatus = (status: EntityState['status']): number => ({
  nominal: 0x48d597,
  warning: 0xf2c94c,
  critical: 0xff8a4c,
  dead: 0xff4d67,
}[status]);

export function SettlementMap({ entities, onSelect }: SettlementMapProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement>(null);
  const entitiesRef = useRef(entities);
  const onSelectRef = useRef(onSelect);
  const updateStageRef = useRef<((currentEntities: readonly EntityState[]) => void) | null>(null);
  const viewportRef = useRef<Viewport | null>(null);
  const objectsRef = useRef(new Map<string, Graphics>());
  const [viewportReady, setViewportReady] = useState(false);
  entitiesRef.current = entities;
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
      if (disposed) return;
      initialized = true;
      const viewport = new Viewport({ events: application.renderer.events, screenWidth: 900, screenHeight: 650, worldWidth: 1_500, worldHeight: 1_200 });
      viewportRef.current = viewport;
      setViewportReady(true);
      viewport.drag().pinch().wheel().decelerate();
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
        for (const entity of currentEntities) {
          if (entity.type === 'heater' || entity.type === 'kettle') continue;
          let displayObject = objectsRef.current.get(entity.id);
          if (displayObject === undefined) {
            displayObject = new Graphics();
            displayObject.eventMode = 'static';
            displayObject.cursor = 'pointer';
            displayObject.on('pointertap', () => onSelectRef.current(entity.id));
            objectsRef.current.set(entity.id, displayObject);
            scene.addChild(displayObject);
            if (entity.type === 'house') {
              const label = new Text({ text: entity.id.replace('house-', '#'), style: { fill: 0xb8c7dc, fontSize: 8 } });
              label.position.set(8, -5);
              displayObject.addChild(label);
            }
          }
          const radius = entity.type === 'house' ? 16 : entity.type === 'power_node' ? 10 : 5;
          displayObject.clear().circle(0, 0, radius).fill({ color: colorForStatus(entity.status), alpha: entity.type === 'house' ? 0.55 : 0.9 });
          displayObject.position.set(entity.coordinates.x, entity.coordinates.y);
          displayObject.alpha = entity.status === 'dead' ? 0.45 : 1;
        }
      };
      updateStage(entitiesRef.current);
      updateStageRef.current = updateStage;
    }).catch(() => {
      if (!disposed) host.textContent = 'Не удалось инициализировать WebGL-сцену';
    });
    return () => {
      disposed = true;
      updateStageRef.current = null;
      viewportRef.current = null;
      setViewportReady(false);
      objectsRef.current.clear();
      resizeObserver?.disconnect();
      if (initialized) application.destroy(true, { children: true });
      host.replaceChildren();
    };
  }, []);

  useEffect(() => {
    updateStageRef.current?.(entities);
  }, [entities]);

  return (
    <div className="visualization-shell">
      <div ref={hostRef} className="map-host" aria-label="2D карта поселения" />
      <NavigationControls
        disabled={!viewportReady}
        onZoomIn={() => viewportRef.current?.zoom(1.2)}
        onZoomOut={() => viewportRef.current?.zoom(0.8)}
        onReset={() => {
          const viewport = viewportRef.current;
          if (viewport === null) return;
          viewport.moveCenter(750, 600);
          viewport.fit(true);
        }}
      />
    </div>
  );
}
