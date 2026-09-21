import { useEffect, useMemo, useRef, useState, type JSX } from 'react';
import { MockDataGenerator } from './domain/mockGenerator';
import { StateManager } from './domain/stateManager';
import type { EntityLog, EntityState, WorldEvent } from './domain/types';
import type { CausalChainStep } from './domain/types';
import { createCausalChain } from './domain/causalChain';
import { createLiveCausalChain } from './domain/liveCausalChain';
import { LiveBrokerSource, type BrokerHealth, type DataSourceMode } from './domain/dataSource';
import { SettlementMap } from './components/SettlementMap';
import { NetworkTopology } from './components/NetworkTopology';
import { Sidebar } from './components/Sidebar';
import { SourceSwitcher } from './components/SourceSwitcher';
import { RunControls, type RunCommand } from './components/RunControls';

export function App(): JSX.Element {
  const managerRef = useRef(new StateManager());
  const generatorRef = useRef(new MockDataGenerator());
  const [entities, setEntities] = useState<readonly EntityState[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [logs, setLogs] = useState<readonly EntityLog[]>([]);
  const [worldEvents, setWorldEvents] = useState<readonly WorldEvent[]>([]);
  const [tab, setTab] = useState<'map' | 'topology'>('map');
  const [sourceMode, setSourceMode] = useState<DataSourceMode>('mock');
  const [brokerHealth, setBrokerHealth] = useState<BrokerHealth>();
  const brokerAvailable = brokerHealth !== undefined;
  const [sourceWarning, setSourceWarning] = useState<string>();
  const [tickId, setTickId] = useState(0);
  const [runMeta, setRunMeta] = useState({ revision: 0, seed: '', runtimeMode: 'mock' });
  const mapFocusRef = useRef<((entityId: string) => void) | undefined>(undefined);
  const graphFocusRef = useRef<((entityId: string) => void) | undefined>(undefined);
  const liveSourceRef = useRef<LiveBrokerSource | null>(null);

  useEffect(() => {
    const manager = managerRef.current;
    const generator = generatorRef.current;
    const unsubscribeGraphics = manager.subscribe((snapshot) => {
      setEntities([...snapshot.entities.values()]);
      setTickId(snapshot.tickId);
      setRunMeta({ revision: snapshot.revision, seed: snapshot.seed, runtimeMode: snapshot.runtimeMode });
      setWorldEvents((previous) => previous.length === snapshot.events.length && previous.at(-1)?.id === snapshot.events.at(-1)?.id
        ? previous : [...snapshot.events]);
    }, { animationFrame: true });
    const unsubscribeSidebar = manager.subscribe((snapshot) => {
      setLogs([...snapshot.logs]);
    }, { throttleMs: 200 });
    const unsubscribeGenerator = generator.subscribe((batch) => manager.ingest(batch));
    manager.ingest(generator.getInitialBatch());
    const stop = generator.start(300);
    return () => {
      stop();
      unsubscribeGenerator();
      unsubscribeGraphics();
      unsubscribeSidebar();
    };
  }, []);

  useEffect(() => {
    const live = new LiveBrokerSource({
      healthUrl: '/health',
      streamUrl: '/stream',
      controlUrl: '/control',
      onHealthChange: setBrokerHealth,
      onBatch: (batch) => { setSourceWarning(undefined); managerRef.current.ingest(batch); },
      onError: (message) => {
        setSourceWarning(message);
      },
    });
    liveSourceRef.current = live;
    const stopHealth = live.startHealthCheck();
    return () => {
      stopHealth();
      live.disconnect();
      liveSourceRef.current = null;
    };
  }, []);

  const switchSource = (requested: DataSourceMode): void => {
    // An unavailable broker means "stay on (or fall back to) the mock"; the full switch below
    // also closes a live stream that may still be reconnecting, so the two sources never mix.
    const mode: DataSourceMode = requested === 'live' && !brokerAvailable ? 'mock' : requested;
    setSourceWarning(mode !== requested ? 'Live Broker недоступен: продолжаем работу в режиме Mock' : undefined);
    if (mode === sourceMode) return;
    setSourceMode(mode);
    managerRef.current.reset();
    setSelectedId(undefined);
    if (mode === 'mock') {
      liveSourceRef.current?.disconnect();
      managerRef.current.ingest(generatorRef.current.getInitialBatch());
      generatorRef.current.start(300);
    } else {
      generatorRef.current.stop();
      liveSourceRef.current?.connect();
    }
  };

  const runControl = async (command: RunCommand): Promise<void> => {
    const live = liveSourceRef.current;
    if (live === null) return;
    if (command === 'restart') {
      // A restart is a new run that starts running at once; the state manager drops the old one by its run ID.
      if (await live.control('reset') !== undefined) await live.control('resume');
    } else {
      await live.control(command);
    }
  };

  const selected = useMemo(() => entities.find((entity) => entity.id === selectedId), [entities, selectedId]);
  const devices = useMemo(
    () => entities.filter((entity) => entity.parentId === selected?.id && (entity.type === 'heater' || entity.type === 'kettle')),
    [entities, selected],
  );
  const causalChain = useMemo(
    () => sourceMode === 'mock' ? createCausalChain(entities) : createLiveCausalChain(worldEvents),
    [entities, sourceMode, worldEvents],
  );
  const selectEntity = (entityId: string): void => {
    setSelectedId(entityId);
    const entity = managerRef.current.getEntity(entityId);
    if (entity !== undefined) {
      managerRef.current.appendLog({ timestamp: Date.now(), entityId, level: 'info', message: `Открыта сущность ${entity.id}` });
      setLogs([...managerRef.current.snapshot().logs]);
    }
  };
  const focusCausalStep = (step: CausalChainStep): void => {
    setTab(step.focus === 'graph' ? 'topology' : 'map');
    window.setTimeout(() => {
      if (step.focus === 'graph') graphFocusRef.current?.(step.focusEntityId);
      else mapFocusRef.current?.(step.focusEntityId);
    }, 50);
  };
  const exportCsv = (): void => {
    const rows = [['entity_id', 'type', 'pid', 'repair_cost', 'water_level', 'temperature', 'power_consumption', 'health']];
    for (const entity of entities) {
      rows.push([
        entity.id,
        entity.type,
        String(entity.pid ?? ''),
        String(entity.metrics.repair_cost ?? ''),
        String(entity.metrics.water_level ?? ''),
        String(entity.metrics.temperature ?? ''),
        String(entity.metrics.power_consumption ?? ''),
        String(entity.metrics.health ?? ''),
      ]);
    }
    const csv = rows.map((row) => row.map((value) => `"${value.replaceAll('"', '""')}"`).join(',')).join('\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `lv426-report-tick-${tickId}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  };

  return (
    <main className="shell">
      <header className="topbar"><div><span className="eyebrow">WORLD KERNEL / LV-426</span><h1>Settlement monitor</h1><div className="run-meta">seed: <strong>{runMeta.seed || '—'}</strong> · tick: <strong>{tickId}</strong></div></div><div className="topbar-right">{sourceMode === 'live' && <RunControls health={brokerHealth} currentTick={tickId} onControl={(command) => void runControl(command)} />}<SourceSwitcher mode={sourceMode} brokerAvailable={brokerAvailable} warning={sourceWarning} onChange={switchSource} /><div className="live-indicator"><span /> {sourceMode === 'live' ? runMeta.runtimeMode.toUpperCase() : 'MOCK'} · {entities.length} entities</div></div></header>
      <div className="content">
        <section className="workspace">
          <nav className="tabs"><button className={tab === 'map' ? 'active' : ''} onClick={() => setTab('map')}>2D карта</button><button className={tab === 'topology' ? 'active' : ''} onClick={() => setTab('topology')}>Топология сетей</button></nav>
          {tab === 'map' ? <SettlementMap key={runMeta.revision} entities={entities} selectedId={selectedId} onSelect={selectEntity} onRegisterFocus={(focus) => { mapFocusRef.current = focus; }} /> : <NetworkTopology key={runMeta.revision} entities={entities} selectedId={selectedId} onSelect={selectEntity} onRegisterFocus={(focus) => { graphFocusRef.current = focus; }} />}
        </section>
        <Sidebar selected={selected} devices={devices} logs={logs} causalChain={causalChain} causalEmptyText={sourceMode === 'live' ? 'Поломок и гибели пока не было. Цепочка причин появится после первой.' : undefined} onFocusCausalStep={focusCausalStep} onExportCsv={exportCsv} />
      </div>
    </main>
  );
}
