import { useEffect, useMemo, useRef, useState, type JSX } from 'react';
import { MockDataGenerator } from './domain/mockGenerator';
import { StateManager } from './domain/stateManager';
import type { EntityLog, EntityState } from './domain/types';
import type { CausalChainStep } from './domain/types';
import { createCausalChain } from './domain/causalChain';
import { LiveBrokerSource, type DataSourceMode } from './domain/dataSource';
import { SettlementMap } from './components/SettlementMap';
import { NetworkTopology } from './components/NetworkTopology';
import { Sidebar } from './components/Sidebar';
import { SourceSwitcher } from './components/SourceSwitcher';

export function App(): JSX.Element {
  const managerRef = useRef(new StateManager());
  const generatorRef = useRef(new MockDataGenerator());
  const [entities, setEntities] = useState<readonly EntityState[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [logs, setLogs] = useState<readonly EntityLog[]>([]);
  const [tab, setTab] = useState<'map' | 'topology'>('map');
  const [sourceMode, setSourceMode] = useState<DataSourceMode>('mock');
  const [brokerAvailable, setBrokerAvailable] = useState(false);
  const [sourceWarning, setSourceWarning] = useState<string>();
  const [tickId, setTickId] = useState(0);
  const mapFocusRef = useRef<((entityId: string) => void) | undefined>(undefined);
  const graphFocusRef = useRef<((entityId: string) => void) | undefined>(undefined);
  const liveSourceRef = useRef<LiveBrokerSource | null>(null);

  useEffect(() => {
    const manager = managerRef.current;
    const generator = generatorRef.current;
    const unsubscribeGraphics = manager.subscribe((snapshot) => {
      setEntities([...snapshot.entities.values()]);
      setTickId(snapshot.tickId);
    }, { animationFrame: true });
    const unsubscribeSidebar = manager.subscribe((snapshot) => {
      setLogs(snapshot.logs);
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
      onHealthChange: setBrokerAvailable,
      onBatch: (batch) => managerRef.current.ingest(batch),
      onError: (message) => {
        setSourceWarning(message);
        setSourceMode('mock');
        liveSourceRef.current?.disconnect();
        generatorRef.current.start(300);
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

  const switchSource = (mode: DataSourceMode): void => {
    if (mode === 'live' && !brokerAvailable) {
      setSourceWarning('Live Broker недоступен: продолжаем работу в режиме Mock');
      setSourceMode('mock');
      generatorRef.current.start(300);
      return;
    }
    setSourceWarning(undefined);
    setSourceMode(mode);
    if (mode === 'mock') {
      liveSourceRef.current?.disconnect();
      generatorRef.current.start(300);
    }
    else {
      generatorRef.current.stop();
      liveSourceRef.current?.connect();
    }
  };

  const selected = useMemo(() => entities.find((entity) => entity.id === selectedId), [entities, selectedId]);
  const devices = useMemo(
    () => entities.filter((entity) => entity.parentId === selected?.id && (entity.type === 'heater' || entity.type === 'kettle')),
    [entities, selected],
  );
  const causalChain = useMemo(() => createCausalChain(entities), [entities]);
  const selectEntity = (entityId: string): void => {
    setSelectedId(entityId);
    const entity = managerRef.current.getEntity(entityId);
    if (entity !== undefined) {
      managerRef.current.appendLog({ timestamp: Date.now(), entityId, level: 'info', message: `Открыта сущность ${entity.id}` });
      setLogs(managerRef.current.snapshot().logs);
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
    const rows = [['entity_id', 'owner_id', 'type', 'pid', 'shared_cost', 'repair_cost', 'water_level', 'temperature']];
    for (const entity of entities) {
      rows.push([
        entity.id,
        entity.type === 'house' ? `owner-${entity.id}` : 'settlement',
        entity.type,
        String(entity.pid),
        entity.type === 'power_node' ? String(entity.metrics.repair_cost ?? 0) : '0',
        String(entity.metrics.repair_cost ?? 0),
        String(entity.metrics.water_level ?? ''),
        String(entity.metrics.temperature ?? ''),
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
      <header className="topbar"><div><span className="eyebrow">WORLD KERNEL / LV-426</span><h1>Settlement monitor</h1><div className="run-meta">seed: <strong>{generatorRef.current.simulationSeed}</strong> · tick: <strong>{tickId}</strong></div></div><div className="topbar-right"><SourceSwitcher mode={sourceMode} brokerAvailable={brokerAvailable} warning={sourceWarning} onChange={switchSource} /><div className="live-indicator"><span /> {sourceMode === 'live' ? 'LIVE' : 'MOCK'} · {entities.length} entities</div></div></header>
      <div className="content">
        <section className="workspace">
          <nav className="tabs"><button className={tab === 'map' ? 'active' : ''} onClick={() => setTab('map')}>2D карта</button><button className={tab === 'topology' ? 'active' : ''} onClick={() => setTab('topology')}>Топология сетей</button></nav>
          {tab === 'map' ? <SettlementMap entities={entities} selectedId={selectedId} onSelect={selectEntity} onRegisterFocus={(focus) => { mapFocusRef.current = focus; }} /> : <NetworkTopology entities={entities} selectedId={selectedId} onSelect={selectEntity} onRegisterFocus={(focus) => { graphFocusRef.current = focus; }} />}
        </section>
        <Sidebar selected={selected} devices={devices} logs={logs} causalChain={causalChain} onFocusCausalStep={focusCausalStep} onExportCsv={exportCsv} />
      </div>
    </main>
  );
}
