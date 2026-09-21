import { FixedSizeList } from 'react-window';
import { useState, type JSX } from 'react';
import type { EntityLog, EntityState } from '../domain/types';
import type { CausalChainStep } from '../domain/types';
import { CausalChainTracker } from './CausalChainTracker';

interface SidebarProps {
  selected: EntityState | undefined;
  devices: readonly EntityState[];
  logs: readonly EntityLog[];
  causalChain: readonly CausalChainStep[];
  onFocusCausalStep: (step: CausalChainStep) => void;
  onExportCsv: () => void;
}

export function Sidebar({ selected, devices, logs, causalChain, onFocusCausalStep, onExportCsv }: SidebarProps): JSX.Element {
  const [activeTab, setActiveTab] = useState<'details' | 'causal'>('details');
  if (selected === undefined) {
    return <aside className="sidebar"><button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button><CausalChainTracker steps={causalChain} onFocus={onFocusCausalStep} /><p className="muted">Выберите дом или узел на карте.</p></aside>;
  }
  return (
    <aside className="sidebar">
      <nav className="sidebar-tabs"><button type="button" className={activeTab === 'details' ? 'active' : ''} onClick={() => setActiveTab('details')}>Сущность</button><button type="button" className={activeTab === 'causal' ? 'active' : ''} onClick={() => setActiveTab('causal')}>Логика каскада событий</button></nav>
      {activeTab === 'causal' ? <CausalChainTracker steps={causalChain} onFocus={onFocusCausalStep} /> : <>
      <button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button>
      <div className="sidebar-heading">
        <div><span className="eyebrow">{selected.type}</span><h2>{selected.id}</h2></div>
        <span className={`status status-${selected.status}`}>{selected.status}</span>
      </div>
      <p className="pid">PID процесса: <strong>{selected.pid ?? '—'}</strong></p>
      <section><h3>Метрики</h3><Metric label="Температура" value={selected.metrics.temperature} suffix=" °C" /><Metric label="Потребление" value={selected.metrics.power_consumption} suffix=" W" /><Metric label="Вода" value={selected.metrics.water_level} suffix=" %" /><Metric label="Стресс" value={selected.metrics.stress} /></section>
      {devices.length > 0 && <section><h3>Приборы</h3>{devices.map((device) => <div className="device" key={device.id}><strong>{device.type}</strong><span>PID {device.pid ?? '—'}</span><span>{device.metrics.power_consumption ?? 0} W</span></div>)}</section>}
      <section className="logs"><h3>Логи ({logs.length})</h3><FixedSizeList height={220} width="100%" itemCount={logs.length} itemSize={42} itemData={logs}>{({ index, style, data }) => <div style={style} className={`log log-${data[index].level}`}><time>{new Date(data[index].timestamp).toLocaleTimeString()}</time><span>{data[index].message}</span></div>}</FixedSizeList></section>
      </>}
    </aside>
  );
}

function Metric({ label, value, suffix = '' }: { label: string; value: number | undefined; suffix?: string }): JSX.Element {
  return <div className="metric"><span>{label}</span><strong>{value === undefined ? '—' : `${value.toFixed(1)}${suffix}`}</strong></div>;
}
