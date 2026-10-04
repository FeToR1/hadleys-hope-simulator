import { useEffect, useMemo, useRef, useState, type JSX } from 'react';
import { Application, Circle, Container, Graphics, GraphicsContext, Text } from 'pixi.js';
import { Viewport } from 'pixi-viewport';
import type { EntityState } from '../domain/types';
import { DEFAULT_MAP_LAYERS, fenceSegments, isMobile, MAP_STATUS, MAP_TYPES, mapIconSvg, mapKind,
  MOBILE_LAYERS, shortMapLabel, type MapKind, type MapLayers } from '../domain/mapPresentation';
import { NavigationControls } from './NavigationControls';
import { MapSceneIndex, type SceneMarker } from '../domain/mapScene';
import { ECOLOGY_LAYER_METRICS, EcologyZoneIndex, summarizeEcologyZones, type EcologyMetric, type EcologyZoneLayer } from '../domain/ecologyZones';

interface SettlementMapProps {
  entities: readonly EntityState[];
  onSelect: (entityId: string) => void;
  selectedId?: string;
  onClearSelection?: () => void;
  onRegisterFocus?: (focus: (entityId: string) => void) => void;
}

interface MarkerView {
  root: Container; icon: Graphics; outline: Graphics; label: Text; badge: Text; status: Text;
  kind: MapKind; decoration: string;
}

type EcologyLayers = Record<EcologyZoneLayer, boolean>;
const DEFAULT_ECOLOGY_LAYERS: EcologyLayers = { forest: false, manure: false, plankton: false };
const ECOLOGY_COLORS: Record<EcologyZoneLayer, number> = { forest: 0x22c55e, manure: 0xa16207, plankton: 0x06b6d4 };
const ECOLOGY_LABELS: Record<EcologyZoneLayer, string> = { forest: 'Лес', manure: 'Навоз', plankton: 'Планктон' };

function drawEcologyLayer(graphics: Graphics, index: EcologyZoneIndex | undefined, visible: ReturnType<EcologyZoneIndex['visible']>, layer: EcologyZoneLayer): void {
  graphics.clear();
  if (!index) return;
  const metric: EcologyMetric = ECOLOGY_LAYER_METRICS[layer];
  const maximum = index.max(metric);
  if (maximum <= 0) return;
  for (const zone of visible) {
    const value = zone.entity.metrics[metric];
    if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) continue;
    const strength = Math.sqrt(Math.min(1, value / maximum));
    graphics.rect(zone.x, zone.y, zone.width, zone.height)
      .fill({ color: ECOLOGY_COLORS[layer], alpha: 0.10 + strength * 0.38 })
      .stroke({ color: ECOLOGY_COLORS[layer], width: 1.2, alpha: 0.18 + strength * 0.42 });
  }
}

function drawSelectedEcologyZone(graphics: Graphics, entity: EntityState | undefined): void {
  graphics.clear();
  if (entity?.type !== 'ecology_zone') return;
  const { min_x: x, min_y: y, max_x: right, max_y: bottom } = entity.metrics;
  if (![x, y, right, bottom].every((value) => typeof value === 'number' && Number.isFinite(value)) ||
    (right as number) <= (x as number) || (bottom as number) <= (y as number)) return;
  graphics.rect(x as number, y as number, (right as number) - (x as number), (bottom as number) - (y as number))
    .fill({ color: 0xe2efff, alpha: 0.07 }).stroke({ color: 0xe2efff, width: 3, alpha: 0.95 });
}

function MapIcon({ kind }: { kind: MapKind }): JSX.Element {
  return <span className="map-symbol" aria-hidden="true" dangerouslySetInnerHTML={{ __html: mapIconSvg(kind) }} />;
}

export function SettlementMap({ entities, onSelect, selectedId, onClearSelection, onRegisterFocus }: SettlementMapProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement>(null);
  const currentRef = useRef({ entities, selectedId, onSelect, onRegisterFocus });
  currentRef.current = { entities, selectedId, onSelect, onRegisterFocus };
  const [layers, setLayers] = useState<MapLayers>(DEFAULT_MAP_LAYERS);
  const [ecologyLayers, setEcologyLayers] = useState<EcologyLayers>(DEFAULT_ECOLOGY_LAYERS);
  const [labels, setLabels] = useState(false);
  const [legendOpen, setLegendOpen] = useState(true);
  const displayRef = useRef({ layers, labels, ecologyLayers });
  displayRef.current = { layers, labels, ecologyLayers };
  const redrawRef = useRef<(() => void) | null>(null);
  const viewportRef = useRef<Viewport | null>(null);
  const fitRef = useRef<(() => void) | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [tooltip, setTooltip] = useState<{ id: string; count: number; clustered: boolean; overview?: boolean;
    composition?: Partial<Record<EntityState['type'], number>>; x: number; y: number }>();

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let disposed = false;
    let initialized = false;
    let fitted = false;
    let resizeObserver: ResizeObserver | undefined;
    const app = new Application();
    const objects = new Map<string, MarkerView>();
    let markers = new Map<string, SceneMarker>();
    let indexedEntities: readonly EntityState[] | undefined;
    let indexedLayers: MapLayers | undefined;
    let indexedSelection: string | undefined;
    let index: MapSceneIndex;
    let ecologyIndex: EcologyZoneIndex | undefined;
    let ecologyIndexedEntities: readonly EntityState[] | undefined;
    let entityById = new Map<string, EntityState>();
    let cachedFenceSegments: ReturnType<typeof fenceSegments> = [];
    let cachedFogEntity: EntityState | undefined;
    let cachedSeaCoastX: number | undefined;
    let redrawFrame = 0;
    const iconContexts = new Map<string, GraphicsContext>();
    const contextFor = (kind: MapKind, status: EntityState['status']): GraphicsContext => {
      const key = kind === 'power_node' ? `${kind}:${status}` : kind;
      let context = iconContexts.get(key);
      if (!context) {
        context = new GraphicsContext();
        if (kind === 'power_node') context.circle(18, 18, 12).fill(MAP_STATUS[status].color);
        else context.svg(mapIconSvg(kind));
        iconContexts.set(key, context);
      }
      return context;
    };
    let gridBounds = '';
    let gridEntitySnapshot: readonly EntityState[] | undefined;
    let gridStateKey = '';
    app.init({ width: 900, height: 650, background: 0x0c1520, antialias: true,
      resolution: Math.min(window.devicePixelRatio || 1, 1.5), autoDensity: true }).then(() => {
      if (disposed) { app.destroy(true, { children: true }); return; }
      initialized = true;
      const viewport = new Viewport({ events: app.renderer.events, screenWidth: host.clientWidth, screenHeight: host.clientHeight,
        worldWidth: 1800, worldHeight: 1500 });
      viewportRef.current = viewport;
      viewport.drag().pinch().wheel().decelerate().clampZoom({ minScale: 0.015, maxScale: 4 });
      app.ticker.maxFPS = 30;
      const biomes = new Graphics();
      const grid = new Graphics();
      const forestLayer = new Graphics();
      const manureLayer = new Graphics();
      const planktonLayer = new Graphics();
      const selectedEcologyZone = new Graphics();
      const fence = new Graphics();
      const fog = new Graphics();
      const scene = new Container();
      scene.sortableChildren = true;
      biomes.eventMode = 'none'; grid.eventMode = 'none'; forestLayer.eventMode = 'none'; manureLayer.eventMode = 'none';
      planktonLayer.eventMode = 'none'; selectedEcologyZone.eventMode = 'none'; fence.eventMode = 'none'; fog.eventMode = 'none';
      viewport.addChild(biomes, grid, forestLayer, manureLayer, planktonLayer, selectedEcologyZone, fence, scene, fog);
      app.stage.addChild(viewport);
      host.appendChild(app.canvas);

      const fit = (): void => {
        const list = currentRef.current.entities.filter((entity) => entity.type !== 'heater' && entity.type !== 'kettle' &&
          entity.type !== 'fence' && entity.type !== 'ecology_zone' && entity.type !== 'fog' && !entity.id.startsWith('weather/'));
        if (!list.length) return;
        fitted = true;
        const xs = list.map((entity) => entity.coordinates.x); const ys = list.map((entity) => entity.coordinates.y);
        const minX = Math.min(...xs); const maxX = Math.max(...xs); const minY = Math.min(...ys); const maxY = Math.max(...ys);
        viewport.fit(false, Math.max(280, maxX - minX + 160), Math.max(280, maxY - minY + 180));
        viewport.moveCenter((minX + maxX) / 2, (minY + maxY) / 2);
        if (viewport.scale.x > 2) viewport.setZoom(2, true);
        fitted = true;
        setZoom(viewport.scale.x);
      };

      const redraw = (): void => {
        if (disposed) return;
        const { entities: list, selectedId: selected } = currentRef.current;
        if (!fitted && list.length) fit();
        if (indexedEntities !== list || indexedLayers !== displayRef.current.layers || indexedSelection !== selected) {
          index = new MapSceneIndex(list, displayRef.current.layers, selected);
          indexedEntities = list; indexedLayers = displayRef.current.layers; indexedSelection = selected;
        }
        if (ecologyIndexedEntities !== list) {
          ecologyIndex = new EcologyZoneIndex(list);
          ecologyIndexedEntities = list;
        }
        if (gridEntitySnapshot !== list) {
          entityById = new Map(list.map((entity) => [entity.id, entity]));
          cachedFenceSegments = fenceSegments(list);
          cachedFogEntity = list.find((entity) => entity.id === 'weather/sea_fog' || entity.type === 'fog');
          cachedSeaCoastX = list.find((entity) => typeof entity.metrics.sea_coast_x === 'number' && Number.isFinite(entity.metrics.sea_coast_x))?.metrics.sea_coast_x;
          const houses = list.filter((entity) => entity.type === 'house');
          const xs = houses.map((entity) => entity.coordinates.x); const ys = houses.map((entity) => entity.coordinates.y);
          const bounds = houses.length ? [Math.floor((Math.min(...xs) - 100) / 70) * 70,
            Math.floor((Math.min(...ys) - 100) / 70) * 70, Math.ceil((Math.max(...xs) + 100) / 70) * 70,
            Math.ceil((Math.max(...ys) + 100) / 70) * 70] as [number, number, number, number] : undefined;
          gridBounds = bounds?.join(',') ?? '';
          const nextGridStateKey = `${gridBounds}|${cachedSeaCoastX ?? ''}`;
          if (gridStateKey !== nextGridStateKey) {
            gridStateKey = nextGridStateKey; grid.clear(); biomes.clear();
            if (bounds) {
              const [left, top, right, bottom] = bounds;
              const margin = 3000;
              if (cachedSeaCoastX !== undefined) {
                biomes.rect(cachedSeaCoastX, top - margin, margin, (bottom - top) + 2 * margin).fill({ color: 0x071e33, alpha: 0.65 });
                biomes.moveTo(cachedSeaCoastX, top - margin).lineTo(cachedSeaCoastX, bottom + margin).stroke({ color: 0x38bdf8, width: 3, alpha: 0.7 });
              }
              const step = Math.max(70, Math.ceil(Math.max(right - left, bottom - top) / 7000) * 70);
              for (let x = left; x <= right; x += step) grid.moveTo(x, top).lineTo(x, bottom);
              for (let y = top; y <= bottom; y += step) grid.moveTo(left, y).lineTo(right, y);
              grid.stroke({ color: 0x233448, width: 0.8, alpha: 0.45 });
              grid.rect(left, top, right - left, bottom - top).stroke({ color: 0x34485f, width: 1, alpha: 0.55 });
            }
          }
          fog.clear();
          if (cachedFogEntity && cachedFogEntity.status !== 'dead') {
            const cx = cachedFogEntity.coordinates.x; const cy = cachedFogEntity.coordinates.y;
            const rw = Number(cachedFogEntity.metrics?.width ?? 1200) / 2;
            const rh = Number(cachedFogEntity.metrics?.height ?? 1400) / 2;
            fog.ellipse(cx, cy, rw * 1.25, rh * 1.25).fill({ color: 0x475569, alpha: 0.18 });
            fog.ellipse(cx, cy, rw, rh).fill({ color: 0x64748b, alpha: 0.28 });
            fog.ellipse(cx, cy, rw * 0.65, rh * 0.65).fill({ color: 0x94a3b8, alpha: 0.35 });
            fog.ellipse(cx, cy, rw * 0.35, rh * 0.35).fill({ color: 0xcfd8dc, alpha: 0.42 });
          }
          gridEntitySnapshot = list;
        }
        const visible = index.visible(viewport.getVisibleBounds(), viewport.scale.x);
        markers = new Map(visible.map((marker) => [marker.key, marker]));
        host.dataset.renderedMarkers = String(visible.length);
        host.dataset.zoom = String(viewport.scale.x);
        const selectedEntity = entityById.get(selected ?? '');
        const visibleZones = ecologyIndex?.visible(viewport.getVisibleBounds()) ?? [];
        drawEcologyLayer(forestLayer, ecologyIndex, visibleZones, 'forest');
        drawEcologyLayer(manureLayer, ecologyIndex, visibleZones, 'manure');
        drawEcologyLayer(planktonLayer, ecologyIndex, visibleZones, 'plankton');
        drawSelectedEcologyZone(selectedEcologyZone, selectedEntity);
        forestLayer.visible = displayRef.current.ecologyLayers.forest;
        manureLayer.visible = displayRef.current.ecologyLayers.manure;
        planktonLayer.visible = displayRef.current.ecologyLayers.plankton;
        // The perimeter fence: intact segments in steel, breaches in red until a crew repairs them.
        fence.clear();
        const segments = cachedFenceSegments;
        const width = Math.max(2, 2.5 / viewport.scale.x);
        for (const segment of segments.filter((item) => !item.broken)) fence.moveTo(segment.from.x, segment.from.y).lineTo(segment.to.x, segment.to.y);
        if (segments.some((item) => !item.broken)) fence.stroke({ color: 0x8ea3b9, width, alpha: 0.85 });
        for (const segment of segments.filter((item) => item.broken)) fence.moveTo(segment.from.x, segment.from.y).lineTo(segment.to.x, segment.to.y);
        if (segments.some((item) => item.broken)) fence.stroke({ color: 0xff617e, width: width * 1.6, alpha: 0.95 });
        for (const [id, view] of objects) {
          if (!markers.has(id)) { view.root.destroy({ children: true }); objects.delete(id); }
        }
        const scale = viewport.scale.x;
        const occupiedLabels: { x: number; y: number; width: number }[] = [];
        // Reserve the selected label first. Hide colliding labels, not the entities underneath them.
        visible.sort((a, b) => Number(b.entity.id === selected) - Number(a.entity.id === selected));
        for (const { entity, members, key, clustered, position, composition } of visible) {
          const kind = mapKind(entity.type);
          const design = MAP_TYPES[kind];
          let view = objects.get(key);
          if (view && view.kind !== kind) { view.root.destroy({ children: true }); objects.delete(key); view = undefined; }
          if (!view) {
            const root = new Container();
            const icon = new Graphics({ context: contextFor(kind, entity.status) });
            icon.position.set(-18, -18);
            const outline = new Graphics();
            const label = new Text({ text: '', style: { fill: 0xb0c5dc, fontFamily: 'Arial', fontSize: 10, letterSpacing: 1 } });
            label.anchor.set(0.5, 0); label.position.set(0, 22);
            const badge = new Text({ text: '', style: { fill: 0xeff6ff, fontFamily: 'Arial', fontSize: 13, fontWeight: 'bold',
              stroke: { color: 0x0c1520, width: 4 } } });
            badge.anchor.set(0.5); badge.position.set(17, 15);
            const status = new Text({ text: '', style: { fill: 0xffc178, fontFamily: 'Arial', fontSize: 17, fontWeight: 'bold',
              stroke: { color: 0x0c1520, width: 4 } } });
            status.anchor.set(0.5); status.position.set(17, -16);
            root.addChild(outline, icon, label, badge, status);
            root.eventMode = 'static'; root.cursor = 'pointer'; root.hitArea = new Circle(0, 0, 23);
            root.on('pointertap', () => {
              const marker = markers.get(key);
              if (!marker) return;
              host.focus({ preventScroll: true });
              if (marker.clustered) {
                viewport.setZoom(Math.min(4, viewport.scale.x * 2));
                viewport.moveCenter(marker.position.x, marker.position.y); scheduleRedraw();
              } else currentRef.current.onSelect(marker.entity.id);
            });
            root.on('pointerover', (event) => {
              const marker = markers.get(key);
              if (marker) setTooltip({ id: marker.entity.id, count: marker.members.length, clustered: marker.clustered,
                overview: Boolean(marker.composition), composition: marker.composition,
                x: Math.max(8, Math.min(event.global.x + 16, host.clientWidth - 244)),
                y: Math.max(8, Math.min(event.global.y + 16, host.clientHeight - 250)) });
            });
            root.on('pointerout', () => setTooltip(undefined));
            view = { root, icon, outline, label, badge, status, kind, decoration: '' };
            objects.set(key, view); scene.addChild(root);
          }
          const chosen = entity.id === selected;
          const decoration = `${entity.status}:${chosen}`;
          if (decoration !== view.decoration) {
            view.outline.clear();
            if (chosen) view.outline.roundRect(-23, -23, 46, 46, 7).fill({ color: 0xc5e2fa, alpha: 0.08 })
              .stroke({ color: 0xe2efff, width: 1.8 });
            if (entity.status !== 'nominal') view.outline.circle(0, 0, 20).stroke({ color: MAP_STATUS[entity.status].color, width: 1.5, alpha: 0.8 });
            view.status.text = MAP_STATUS[entity.status].mark;
            view.status.style.fill = MAP_STATUS[entity.status].color;
            // Existing utility nodes retain their current status colours and simple appearance.
            if (kind === 'power_node') view.icon.context = contextFor(kind, entity.status);
            view.decoration = decoration;
          }
          const related = !selected || chosen || entity.connectedTo.includes(selected) || selectedEntity?.connectedTo.includes(entity.id);
          view.root.alpha = related ? 1 : 0.7;
          view.icon.alpha = entity.status === 'dead' ? 0.45 : 1;
          view.root.position.set(position.x, position.y);
          // Minimum screen sizes keep threats and vehicles readable in the colony overview.
          const minimum = kind === 'mine' ? 54 : kind === 'house' ? 16 : kind === 'civilian' ? 10 : kind === 'power_node' ? 8 : (kind === 'air_defense' || kind === 'depository' || kind === 'medical_center') ? 22 : 17;
          view.root.scale.set(Math.max(design.size, minimum / scale) / 36);
          view.root.zIndex = chosen ? 100 : kind === 'house' ? 1 : kind === 'power_node' ? 0 : (kind === 'xenomorph' || kind === 'predator' || kind === 'crocodile') ? 25 : kind === 'air_defense' ? 15 : 10;
          view.label.text = clustered ? '' : shortMapLabel(entity);
          view.label.scale.set(1 / (view.root.scale.x * scale));
          const labelBox = { x: entity.coordinates.x * scale, y: entity.coordinates.y * scale + 22 * view.root.scale.x * scale,
            width: view.label.text.length * 6 };
          view.label.visible = chosen || kind === 'mine' || (displayRef.current.labels && scale >= 0.65 && !occupiedLabels.some((box) =>
            Math.abs(box.y - labelBox.y) < 15 && Math.abs(box.x - labelBox.x) < (box.width + labelBox.width) / 2 + 5));
          if (view.label.visible) occupiedLabels.push(labelBox);
          const passengers = entity.metrics.passenger_count ?? 0;
          view.badge.text = kind === 'mine' ? String(entity.metrics.workers ?? 0) : members.length > 1
            ? String(composition ? (composition.house ?? members.length) : members.length) : kind === 'rover' && passengers > 0 ? String(passengers) : '';
          view.badge.scale.set(1 / (view.root.scale.x * scale));
          view.status.scale.set(1 / (view.root.scale.x * scale));
        }
      };
      const scheduleRedraw = (): void => {
        if (!redrawFrame && !disposed) redrawFrame = requestAnimationFrame(() => { redrawFrame = 0; redraw(); });
      };
      redrawRef.current = scheduleRedraw;
      fitRef.current = () => { fit(); scheduleRedraw(); setTooltip(undefined); };
      viewport.on('zoomed', () => { setZoom(viewport.scale.x); setTooltip(undefined); scheduleRedraw(); });
      viewport.on('clicked', ({ world, event }) => {
        // Marker pointer taps take precedence over a zone behind the marker.
        let target: typeof event.target | undefined = event.target;
        while (target && target !== scene) {
          if (target.parent === scene) return;
          target = target.parent ?? undefined;
        }
        const zone = ecologyIndex?.at(world, displayRef.current.ecologyLayers);
        if (zone) currentRef.current.onSelect(zone.entity.id);
      });
      viewport.on('moved', scheduleRedraw);
      viewport.on('drag-start', () => setTooltip(undefined));
      resizeObserver = new ResizeObserver(() => {
        if (disposed) return;
        const width = Math.max(1, host.clientWidth); const height = Math.max(1, host.clientHeight);
        app.renderer.resize(width, height); viewport.resize(width, height); scheduleRedraw();
      });
      app.renderer.resize(Math.max(1, host.clientWidth), Math.max(1, host.clientHeight));
      viewport.resize(Math.max(1, host.clientWidth), Math.max(1, host.clientHeight));
      resizeObserver.observe(host);
      redraw(); setReady(true);
      currentRef.current.onRegisterFocus?.((id) => {
        if (disposed) return;
        const entity = currentRef.current.entities.find((item) => item.id === id);
        if (!entity) return;
        if (isMobile(entity.type)) setLayers((previous) => ({ ...previous, [entity.type]: true }));
        viewport.setZoom(1.5); viewport.moveCenter(entity.coordinates.x, entity.coordinates.y); setZoom(viewport.scale.x); scheduleRedraw();
      });
    }).catch(() => { if (!disposed) setError(true); });
    return () => {
      disposed = true; resizeObserver?.disconnect(); redrawRef.current = null; fitRef.current = null; viewportRef.current = null;
      if (redrawFrame) cancelAnimationFrame(redrawFrame);
      objects.clear();
      if (initialized) app.destroy(true, { children: true });
      for (const context of iconContexts.values()) context.destroy();
      host.replaceChildren();
    };
  }, []);

  useEffect(() => { redrawRef.current?.(); }, [entities, selectedId, layers, labels, ecologyLayers]);
  useEffect(() => { setTooltip(undefined); }, [layers, ecologyLayers]);

  const counts = useMemo(() => entities.reduce((result, entity) => {
    result[entity.type] = (result[entity.type] ?? 0) + 1; return result;
  }, {} as Record<string, number>), [entities]);
  const ecologySummary = useMemo(() => summarizeEcologyZones(entities), [entities]);
  const hovered = tooltip && entities.find((entity) => entity.id === tooltip.id);
  const scaleMeters = [10, 20, 50, 100, 200, 500, 1000].find((value) => value * zoom >= 65) ?? 1000;
  const zoomTo = (value: number): void => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    viewport.setZoom(value, true);
    setZoom(viewport.scale.x);
    redrawRef.current?.();
    setTooltip(undefined);
  };
  return <div className="visualization-shell settlement-map" onKeyDown={(event) => { if (event.key === 'Escape') onClearSelection?.(); }}>
    <div className="map-toolbar">
      <div className="map-title"><span className="eyebrow">СЕКТОР ПОСЕЛЕНИЯ</span><strong>План поселения</strong></div>
      <span className="map-summary">
        <strong>{counts.house ?? 0}</strong> домов <span>·</span> <strong>{counts.civilian ?? 0}</strong> жителей
        {counts.air_defense ? <> <span>·</span> <strong>{counts.air_defense}</strong> ПВО</> : null}
        {counts.crocodile ? <> <span>·</span> <strong style={{ color: '#10b981' }}>{counts.crocodile}</strong> крокодилов</> : null}
        {entities.some((e) => e.metrics?.in_fog) ? <> <span>·</span> <strong style={{ color: '#38bdf8' }}>≋ Морской туман</strong></> : null}
      </span>
      <button type="button" className="map-toggle map-label-toggle" title="Подписи видны при приближении; пересекающиеся скрываются" aria-pressed={labels} onClick={() => setLabels(!labels)}>Aa <span>Подписи</span></button>
      <button type="button" className="map-toggle" aria-expanded={legendOpen} aria-controls="map-legend" onClick={() => setLegendOpen(!legendOpen)}>☷ Легенда</button>
    </div>
    <div className="map-layerbar" aria-label="Слои карты">
      <span className="map-layer-label">Показать</span>
      {MOBILE_LAYERS.filter((kind) => (kind !== 'xenomorph' && kind !== 'predator') || (counts[kind] ?? 0) > 0).map((kind) => <button type="button" key={kind} className="map-layer" aria-pressed={layers[kind]}
        onClick={() => setLayers((previous) => ({ ...previous, [kind]: !previous[kind] }))}>
        <MapIcon kind={kind} /><span>{MAP_TYPES[kind].label}</span><small>{counts[kind] ?? 0}</small>
      </button>)}
      {(['forest', 'manure', 'plankton'] as const).map((layer) => <button type="button" key={layer} className="map-layer ecology-layer"
        aria-pressed={ecologyLayers[layer]} disabled={ecologySummary.zoneCount === 0}
        onClick={() => setEcologyLayers((previous) => ({ ...previous, [layer]: !previous[layer] }))}>
        <i className={`ecology-swatch ecology-${layer}`} /><span>{ECOLOGY_LABELS[layer]}</span>
      </button>)}
      {ecologySummary.zoneCount > 0 && <div className="ecology-summary" aria-live="polite">
        <span>Загрязнённых зон <strong>{ecologySummary.pollutedZones}</strong></span>
        <span>Навоз <strong>{ecologySummary.manureTotal.toFixed(1)}</strong></span>
        <span>Планктон <strong>{ecologySummary.planktonTotal.toFixed(1)}</strong></span>
      </div>}
      <div className="map-future-layers" aria-label="Будущие слои сетей">
        <button type="button" disabled title="Слой линий электропередачи появится позже">ϟ ЛЭП <small>скоро</small></button>
        <button type="button" disabled title="Слой водопровода появится позже">≋ Водопровод <small>скоро</small></button>
      </div>
    </div>
    <div className="map-canvas-shell">
      <div ref={hostRef} className="map-host" aria-label="Карта колонии. Перетаскивайте для перемещения, используйте колесо для масштаба." tabIndex={0} />
      {!ready && <div className="map-placeholder">{error ? 'Не удалось загрузить карту. Обновите страницу.' : 'Загружаем карту колонии…'}</div>}
      {ready && entities.length === 0 && <div className="map-placeholder"><div><strong>Ожидаем симуляцию</strong><p>Запустите backend — карта появится автоматически.</p></div></div>}
      <div className="map-compass" aria-hidden="true"><span>N</span><i>↑</i></div>
      {selectedId && <div className="map-selection"><span>Выбрано</span><strong>{selectedId}</strong><button type="button" onClick={onClearSelection} aria-label="Снять выделение">×</button></div>}
      {legendOpen && <div id="map-legend" className="map-legend">
        <div className="map-legend-heading"><strong>Условные обозначения</strong><button type="button" aria-label="Свернуть легенду" onClick={() => setLegendOpen(false)}>−</button></div>
        <div className="map-legend-grid">{(['house', 'mine', 'civilian', 'rover', 'air_defense', 'crocodile', 'depository', 'medical_center', 'marine', 'power_node', 'fence'] as const).map((kind) =>
          <div key={kind} className="map-legend-item"><MapIcon kind={kind} /><span>{MAP_TYPES[kind].label}</span></div>)}</div>
        {(counts.predator ?? 0) > 0 && <div className="map-legend-item"><MapIcon kind="predator" /><span>{MAP_TYPES.predator.label}</span></div>}
        {(counts.xenomorph ?? 0) > 0 && <div className="map-legend-item"><MapIcon kind="xenomorph" /><span>{MAP_TYPES.xenomorph.label}</span></div>}
        <div className="map-status-legend"><span>Без метки — норма</span><span className="map-warning">! Внимание</span><span className="map-critical">! Критическое</span><span className="map-dead">× Погиб / разрушен</span></div>
        <p>Издалека дома и жители объединяются в группы. Нажмите на группу, чтобы приблизить. Экологические слои показывают только биомассу из данных симуляции.</p>
      </div>}
      {hovered && tooltip && <div className="entity-tooltip map-tooltip" style={{ left: tooltip.x, top: tooltip.y }}>
        <div className="map-tooltip-heading"><MapIcon kind={mapKind(hovered.type)} /><div><strong>{tooltip.overview ? 'Район колонии' : MAP_TYPES[mapKind(hovered.type)].singular}</strong><span>{hovered.id}</span></div></div>
        <span style={{ color: MAP_STATUS[hovered.status].color }}>{MAP_STATUS[hovered.status].label}</span>
        {tooltip.count > 1 && <span>{tooltip.clustered ? 'В группе' : 'В этой точке'}: {tooltip.count}</span>}
        {tooltip.composition && Object.entries(tooltip.composition).map(([type, count]) => <span key={type}>
          {MAP_TYPES[mapKind(type as EntityState['type'])].label}: {count}
        </span>)}
        {hovered.type === 'mine' && <span>Шахтёров на смене: {hovered.metrics.workers ?? 0} (эффективность: {((hovered.metrics.synergy_multiplier ?? 1) as number).toFixed(2)}x)</span>}
        {hovered.type === 'air_defense' && <>
          <span>ПВО: {hovered.metrics.broken ? 'ОТКЛЮЧЕНА (туман)' : 'Боеготовность'}</span>
          {hovered.metrics.in_fog && <span style={{ color: '#f2c94c' }}>В зоне морского тумана</span>}
        </>}
        {hovered.type === 'crocodile' && <span>Воздушная угроза из леса (бомбардировка)</span>}
        {hovered.type === 'depository' && <span>Склад креатина: {hovered.metrics.creatine_stock ?? 0} ед.</span>}
        {hovered.type === 'medical_center' && <span>Медцентр: {hovered.metrics.creatine_stock ?? 0} ед. креатина</span>}
        {hovered.type === 'ecology_zone' && <>
          <span>Лес: {hovered.metrics.forest_biomass?.toFixed(1) ?? 0}</span>
          <span>Навоз: {hovered.metrics.manure?.toFixed(1) ?? 0}</span>
          <span>Планктон: {hovered.metrics.plankton_biomass?.toFixed(1) ?? 0}</span>
        </>}
        {(hovered.type === 'air_defense' || hovered.type === 'ground_turret') && <>
          <span>Боезапас: {hovered.metrics.ammo_remaining ?? hovered.metrics.ammo ?? '—'}</span>
          {hovered.metrics.refusal_reason && <span>Состояние: {defenseReasonLabel(hovered.metrics.refusal_reason)}</span>}
        </>}
        {hovered.metrics.mutation && <span>Мутация: {mutationLabel(hovered.metrics.mutation)}</span>}
        {hovered.type === 'burner' && hovered.metrics.shared_fuel_remaining !== undefined &&
          <span>Топливо очистки: {hovered.metrics.shared_fuel_remaining.toFixed(1)}</span>}
        {hovered.type === 'civilian' && hovered.metrics.available === false && <span>Житель временно недоступен</span>}
        {hovered.type === 'rover' && <>
          <span>{hovered.id.startsWith('cleanup-') ? 'Уборочный ровер' : hovered.id.startsWith('forester-') ? 'Лесной ровер' : hovered.id.startsWith('crew-') ? 'Ремонтный экипаж' : hovered.id.startsWith('cargo-') ? 'Грузовой ровер' : hovered.id.startsWith('transport-') ? 'Пассажирский ровер' : 'Ровер'}</span>
          <span>{hovered.metrics.passenger_capacity !== undefined && <>Пассажиры: {hovered.metrics.passenger_count ?? 0} / {hovered.metrics.passenger_capacity}</>}</span>
          {hovered.metrics.creatine_stock !== undefined && hovered.metrics.creatine_stock > 0 && <span style={{ color: '#f59e0b' }}>Груз креатина: {hovered.metrics.creatine_stock.toFixed(1)} ед.</span>}
        </>}
        {hovered.type === 'marine' && <span>Бойцов в отряде: {hovered.metrics.squad_size ?? '—'}</span>}
        {hovered.metrics.sea_damage !== undefined && hovered.metrics.sea_damage > 0 && <span style={{ color: '#ff617e' }}>Урон от подмыва моря: {hovered.metrics.sea_damage.toFixed(1)}</span>}
        {hovered.metrics.in_fog && <span style={{ color: '#f2c94c' }}>В тумане (респиратор: {hovered.metrics.respirator_equipped ? 'надет' : 'нет'})</span>}
        {hovered.metrics.temperature !== undefined && <span>Температура: {hovered.metrics.temperature.toFixed(1)} °C</span>}
        {hovered.metrics.water_level !== undefined && <span>Вода: {hovered.metrics.water_level.toFixed(0)} %</span>}
        {hovered.metrics.power_consumption !== undefined && <span>Мощность: {hovered.metrics.power_consumption.toFixed(0)} W</span>}
        {hovered.metrics.health !== undefined && <span>Здоровье: {hovered.metrics.health.toFixed(0)} hp</span>}
        {hovered.pid !== null && <span>PID: {hovered.pid}</span>}
        <small>{tooltip.clustered ? 'Нажмите, чтобы приблизить группу' : 'Нажмите, чтобы открыть подробности'}</small>
      </div>}
      <NavigationControls disabled={!ready} onZoomIn={() => zoomTo(Math.min(4, zoom * 1.35))}
        onZoomOut={() => zoomTo(Math.max(0.015, zoom / 1.35))} onReset={() => fitRef.current?.()} />
      <div className="map-footer"><div className="map-scale"><span style={{ width: scaleMeters * zoom }} /><small>{scaleMeters} м</small></div>
        <span className="map-gesture-hint">Перетаскивание — перемещение · Колесо — масштаб</span><span>{Math.round(zoom * 100)}%</span></div>
    </div>
  </div>;
}

function defenseReasonLabel(reason: NonNullable<EntityState['metrics']['refusal_reason']>): string {
  return ({ broken: 'повреждена', no_power: 'нет питания', no_ammo: 'нет боеприпасов', cooldown: 'перезарядка' })[reason];
}

function mutationLabel(mutation: NonNullable<EntityState['metrics']['mutation']>): string {
  return ({ armored: 'бронированная', swift: 'быстрая', venomous: 'ядовитая', pack: 'стайная', baseline: 'обычная' })[mutation];
}
