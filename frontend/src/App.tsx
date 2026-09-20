import { useEffect, useMemo, useRef, useState, type JSX } from 'react';
import { MockDataGenerator } from './domain/mockGenerator';
import { StateManager } from './domain/stateManager';
import type { EntityLog, EntityState } from './domain/types';
import type { CausalChainStep } from './domain/types';
import { createCausalChain } from './domain/causalChain';
import { SettlementMap } from './components/SettlementMap';
import { NetworkTopology } from './components/NetworkTopology';
import { Sidebar } from './components/Sidebar';

export function App(): JSX.Element {
  const managerRef = useRef(new StateManager());
  const generatorRef = useRef(new MockDataGenerator());
  const [entities, setEntities] = useState<readonly EntityState[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [logs, setLogs] = useState<readonly EntityLog[]>([]);
  const [tab, setTab] = useState<'map' | 'topology'>('map');
  const mapFocusRef = useRef<((entityId: string) => void) | undefined>(undefined);
  const graphFocusRef = useRef<((entityId: string) => void) | undefined>(undefined);

  useEffect(() => {
    const manager = managerRef.current;
    const generator = generatorRef.current;
    const unsubscribeState = manager.subscribe((snapshot) => {
      setEntities([...snapshot.entities.values()]);
      setLogs(snapshot.logs);
    });
    const unsubscribeGenerator = generator.subscribe((batch) => manager.ingest(batch));
    manager.ingest(generator.getInitialBatch());
    const stop = generator.start(300);
    return () => {
      stop();
      unsubscribeGenerator();
      unsubscribeState();
    };
  }, []);

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

  return (
    <main className="shell">
      <header className="topbar"><div><span className="eyebrow">WORLD KERNEL / LV-426</span><h1>Settlement monitor</h1></div><div className="live-indicator"><span /> LIVE · {entities.length} entities</div></header>
      <div className="content">
        <section className="workspace">
          <nav className="tabs"><button className={tab === 'map' ? 'active' : ''} onClick={() => setTab('map')}>2D карта</button><button className={tab === 'topology' ? 'active' : ''} onClick={() => setTab('topology')}>Топология сетей</button></nav>
          {tab === 'map' ? <SettlementMap entities={entities} selectedId={selectedId} onSelect={selectEntity} onRegisterFocus={(focus) => { mapFocusRef.current = focus; }} /> : <NetworkTopology entities={entities} selectedId={selectedId} onSelect={selectEntity} onRegisterFocus={(focus) => { graphFocusRef.current = focus; }} />}
        </section>
        <Sidebar selected={selected} devices={devices} logs={logs} causalChain={causalChain} onFocusCausalStep={focusCausalStep} />
      </div>
    </main>
  );
}
