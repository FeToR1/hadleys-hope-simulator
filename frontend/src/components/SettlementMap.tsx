import { useEffect, useRef, useState, type JSX } from 'react';
import { Application, Circle, Container, Graphics, Text } from 'pixi.js';
import { Viewport } from 'pixi-viewport';
import type { EntityState } from '../domain/types';
import { createMapMarkers, DEFAULT_MAP_LAYERS, fenceSegments, isMobile, MAP_STATUS, MAP_TYPES, mapIconSvg, mapKind,
  MOBILE_LAYERS, shortMapLabel, type MapKind, type MapLayers, type MapMarker } from '../domain/mapPresentation';
import { NavigationControls } from './NavigationControls';

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

function MapIcon({ kind }: { kind: MapKind }): JSX.Element {
  return <span className="map-symbol" aria-hidden="true" dangerouslySetInnerHTML={{ __html: mapIconSvg(kind) }} />;
}

export function SettlementMap({ entities, onSelect, selectedId, onClearSelection, onRegisterFocus }: SettlementMapProps): JSX.Element {
  const hostRef = useRef<HTMLDivElement>(null);
  const currentRef = useRef({ entities, selectedId, onSelect, onRegisterFocus });
  currentRef.current = { entities, selectedId, onSelect, onRegisterFocus };
  const [layers, setLayers] = useState<MapLayers>(DEFAULT_MAP_LAYERS);
  const [labels, setLabels] = useState(false);
  const [legendOpen, setLegendOpen] = useState(true);
  const displayRef = useRef({ layers, labels });
  displayRef.current = { layers, labels };
  const redrawRef = useRef<(() => void) | null>(null);
  const viewportRef = useRef<Viewport | null>(null);
  const fitRef = useRef<(() => void) | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [tooltip, setTooltip] = useState<{ id: string; count: number; x: number; y: number }>();

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let disposed = false;
    let initialized = false;
    let fitted = false;
    let resizeObserver: ResizeObserver | undefined;
    const app = new Application();
    const objects = new Map<string, MarkerView>();
    let markers = new Map<string, MapMarker>();
    let gridBounds = '';
    app.init({ width: 900, height: 650, background: 0x0c1520, antialias: true,
      resolution: Math.min(window.devicePixelRatio || 1, 2), autoDensity: true }).then(() => {
      if (disposed) { app.destroy(true, { children: true }); return; }
      initialized = true;
      const viewport = new Viewport({ events: app.renderer.events, screenWidth: host.clientWidth, screenHeight: host.clientHeight,
        worldWidth: 1800, worldHeight: 1500 });
      viewportRef.current = viewport;
      viewport.drag().pinch().wheel().decelerate().clampZoom({ minScale: 0.12, maxScale: 4 });
      const grid = new Graphics();
      const fence = new Graphics();
      const scene = new Container();
      scene.sortableChildren = true;
      grid.eventMode = 'none'; fence.eventMode = 'none';
      viewport.addChild(grid, fence, scene);
      app.stage.addChild(viewport);
      host.appendChild(app.canvas);

      const fit = (): void => {
        const list = currentRef.current.entities.filter((entity) => entity.type !== 'heater' && entity.type !== 'kettle' && entity.type !== 'fence');
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
        const { entities: list, selectedId: selected } = currentRef.current;
        const visible = createMapMarkers(list, displayRef.current.layers, selected);
        markers = new Map(visible.map((marker) => [marker.entity.id, marker]));
        const selectedEntity = list.find((entity) => entity.id === selected);
        // A coordinate grid is a reading aid, not an inferred road or network layout.
        const houses = list.filter((entity) => entity.type === 'house');
        if (houses.length) {
          const xs = houses.map((entity) => entity.coordinates.x); const ys = houses.map((entity) => entity.coordinates.y);
          const bounds = [Math.floor((Math.min(...xs) - 100) / 70) * 70, Math.floor((Math.min(...ys) - 100) / 70) * 70,
            Math.ceil((Math.max(...xs) + 100) / 70) * 70, Math.ceil((Math.max(...ys) + 100) / 70) * 70];
          if (bounds.join(',') !== gridBounds) {
            gridBounds = bounds.join(','); grid.clear();
            const [left, top, right, bottom] = bounds;
            const step = Math.max(70, Math.ceil(Math.max(right - left, bottom - top) / 7000) * 70);
            for (let x = left; x <= right; x += step) grid.moveTo(x, top).lineTo(x, bottom);
            for (let y = top; y <= bottom; y += step) grid.moveTo(left, y).lineTo(right, y);
            grid.stroke({ color: 0x233448, width: 0.8, alpha: 0.45 });
            grid.rect(left, top, right - left, bottom - top).stroke({ color: 0x34485f, width: 1, alpha: 0.55 });
          }
        } else { grid.clear(); gridBounds = ''; }
        // The perimeter fence: intact segments in steel, breaches in red until a crew repairs them.
        fence.clear();
        const segments = fenceSegments(list);
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
        for (const { entity, members } of visible) {
          const kind = mapKind(entity.type);
          const design = MAP_TYPES[kind];
          let view = objects.get(entity.id);
          if (view && view.kind !== kind) { view.root.destroy({ children: true }); objects.delete(entity.id); view = undefined; }
          if (!view) {
            const root = new Container();
            const icon = new Graphics().svg(mapIconSvg(kind));
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
            root.on('pointertap', () => { host.focus({ preventScroll: true }); currentRef.current.onSelect(entity.id); });
            root.on('pointerover', (event) => {
              const marker = markers.get(entity.id);
              if (marker) setTooltip({ id: entity.id, count: marker.members.length,
                x: Math.max(8, Math.min(event.global.x + 16, host.clientWidth - 244)),
                y: Math.max(8, Math.min(event.global.y + 16, host.clientHeight - 250)) });
            });
            root.on('pointerout', () => setTooltip(undefined));
            view = { root, icon, outline, label, badge, status, kind, decoration: '' };
            objects.set(entity.id, view); scene.addChild(root);
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
            if (kind === 'power_node') view.icon.clear().circle(18, 18, 12).fill(MAP_STATUS[entity.status].color);
            view.decoration = decoration;
          }
          const related = !selected || chosen || entity.connectedTo.includes(selected) || selectedEntity?.connectedTo.includes(entity.id);
          view.root.alpha = related ? 1 : 0.7;
          view.icon.alpha = entity.status === 'dead' ? 0.45 : 1;
          view.root.position.set(entity.coordinates.x, entity.coordinates.y);
          // Minimum screen sizes keep threats and vehicles readable in the colony overview.
          const minimum = kind === 'mine' ? 54 : kind === 'house' ? 16 : kind === 'civilian' ? 10 : kind === 'power_node' ? 8 : 17;
          view.root.scale.set(Math.max(design.size, minimum / scale) / 36);
          view.root.zIndex = chosen ? 100 : kind === 'house' ? 1 : kind === 'power_node' ? 0 : kind === 'xenomorph' ? 20 : 10;
          view.label.text = shortMapLabel(entity);
          view.label.scale.set(1 / (view.root.scale.x * scale));
          const labelBox = { x: entity.coordinates.x * scale, y: entity.coordinates.y * scale + 22 * view.root.scale.x * scale,
            width: view.label.text.length * 6 };
          view.label.visible = chosen || kind === 'mine' || (displayRef.current.labels && scale >= 0.65 && !occupiedLabels.some((box) =>
            Math.abs(box.y - labelBox.y) < 15 && Math.abs(box.x - labelBox.x) < (box.width + labelBox.width) / 2 + 5));
          if (view.label.visible) occupiedLabels.push(labelBox);
          const passengers = entity.metrics.passenger_count ?? 0;
          view.badge.text = kind === 'mine' ? String(entity.metrics.workers ?? 0) : members.length > 1 ? String(members.length) : kind === 'rover' && passengers > 0 ? String(passengers) : '';
        }
        if (!fitted && list.length) { fit(); redraw(); }
      };
      redrawRef.current = redraw;
      fitRef.current = () => { fit(); redraw(); setTooltip(undefined); };
      viewport.on('zoomed', () => { setZoom(viewport.scale.x); setTooltip(undefined); redraw(); });
      viewport.on('drag-start', () => setTooltip(undefined));
      resizeObserver = new ResizeObserver(() => {
        if (disposed) return;
        const width = Math.max(1, host.clientWidth); const height = Math.max(1, host.clientHeight);
        app.renderer.resize(width, height); viewport.resize(width, height); redraw();
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
        viewport.setZoom(1.5); viewport.moveCenter(entity.coordinates.x, entity.coordinates.y); setZoom(viewport.scale.x); redraw();
      });
    }).catch(() => { if (!disposed) setError(true); });
    return () => {
      disposed = true; resizeObserver?.disconnect(); redrawRef.current = null; fitRef.current = null; viewportRef.current = null;
      objects.clear();
      if (initialized) app.destroy(true, { children: true });
      host.replaceChildren();
    };
  }, []);

  useEffect(() => { redrawRef.current?.(); }, [entities, selectedId, layers, labels]);
  useEffect(() => { setTooltip(undefined); }, [layers]);

  const counts = entities.reduce((result, entity) => { result[entity.type] = (result[entity.type] ?? 0) + 1; return result; }, {} as Record<string, number>);
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
      <div className="map-title"><span className="eyebrow">LV–426 / HADLEY’S HOPE</span><strong>План колонии</strong></div>
      <span className="map-summary"><strong>{counts.house ?? 0}</strong> домов <span>·</span> <strong>{counts.civilian ?? 0}</strong> жителей</span>
      <button type="button" className="map-toggle map-label-toggle" title="Подписи видны при приближении; пересекающиеся скрываются" aria-pressed={labels} onClick={() => setLabels(!labels)}>Aa <span>Подписи</span></button>
      <button type="button" className="map-toggle" aria-expanded={legendOpen} aria-controls="map-legend" onClick={() => setLegendOpen(!legendOpen)}>☷ Легенда</button>
    </div>
    <div className="map-layerbar" aria-label="Слои карты">
      <span className="map-layer-label">Показать</span>
      {MOBILE_LAYERS.map((kind) => <button type="button" key={kind} className="map-layer" aria-pressed={layers[kind]}
        onClick={() => setLayers((previous) => ({ ...previous, [kind]: !previous[kind] }))}>
        <MapIcon kind={kind} /><span>{MAP_TYPES[kind].label}</span><small>{counts[kind] ?? 0}</small>
      </button>)}
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
        <div className="map-legend-grid">{(['house', 'mine', 'civilian', 'rover', 'marine', 'xenomorph', 'power_node', 'fence'] as const).map((kind) =>
          <div key={kind} className="map-legend-item"><MapIcon kind={kind} /><span>{MAP_TYPES[kind].label}</span></div>)}</div>
        <div className="map-status-legend"><span>Без метки — норма</span><span className="map-warning">! Внимание</span><span className="map-critical">! Критическое</span><span className="map-dead">× Погиб / разрушен</span></div>
        <p>Число у значка — группа, пассажиры или шахтёры на смене.</p>
      </div>}
      {hovered && tooltip && <div className="entity-tooltip map-tooltip" style={{ left: tooltip.x, top: tooltip.y }}>
        <div className="map-tooltip-heading"><MapIcon kind={mapKind(hovered.type)} /><div><strong>{MAP_TYPES[mapKind(hovered.type)].singular}</strong><span>{hovered.id}</span></div></div>
        <span style={{ color: MAP_STATUS[hovered.status].color }}>{MAP_STATUS[hovered.status].label}</span>
        {tooltip.count > 1 && <span>В этой точке: {tooltip.count}</span>}
        {hovered.type === 'mine' && <span>Шахтёров на смене: {hovered.metrics.workers ?? 0}</span>}
        {hovered.type === 'rover' && <span>Пассажиры: {hovered.metrics.passenger_count ?? 0} / {hovered.metrics.passenger_capacity ?? '—'}</span>}
        {hovered.type === 'marine' && <span>Бойцов в отряде: {hovered.metrics.squad_size ?? '—'}</span>}
        {hovered.metrics.temperature !== undefined && <span>Температура: {hovered.metrics.temperature.toFixed(1)} °C</span>}
        {hovered.metrics.water_level !== undefined && <span>Вода: {hovered.metrics.water_level.toFixed(0)} %</span>}
        {hovered.metrics.power_consumption !== undefined && <span>Мощность: {hovered.metrics.power_consumption.toFixed(0)} W</span>}
        {hovered.metrics.health !== undefined && <span>Здоровье: {hovered.metrics.health.toFixed(0)} hp</span>}
        {hovered.pid !== null && <span>PID: {hovered.pid}</span>}
        <small>Нажмите, чтобы открыть подробности</small>
      </div>}
      <NavigationControls disabled={!ready} onZoomIn={() => zoomTo(Math.min(4, zoom * 1.35))}
        onZoomOut={() => zoomTo(Math.max(0.12, zoom / 1.35))} onReset={() => fitRef.current?.()} />
      <div className="map-footer"><div className="map-scale"><span style={{ width: scaleMeters * zoom }} /><small>{scaleMeters} м</small></div>
        <span className="map-gesture-hint">Перетаскивание — перемещение · Колесо — масштаб</span><span>{Math.round(zoom * 100)}%</span></div>
    </div>
  </div>;
}
