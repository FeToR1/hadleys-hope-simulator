import { FixedSizeList } from 'react-window';
import { useMemo, useState, type JSX } from 'react';
import type { AttractorMode, EntityLog, EntityState, PhasePoint, Posting } from '../domain/types';
import type { CausalChainStep } from '../domain/types';
import { formatVmValue } from '../domain/format';
import { CausalChainTracker } from './CausalChainTracker';
import { SpendPanel, formatMoney } from './SpendPanel';
import { AttractorRadar } from './AttractorRadar';
import { AttractorVortex3D } from './AttractorVortex3D';

interface SidebarProps {
  selected: EntityState | undefined;
  devices: readonly EntityState[];
  logs: readonly EntityLog[];
  causalChain: readonly CausalChainStep[];
  causalEmptyText?: string;
  postings: readonly Posting[];
  spendByOwner: ReadonlyMap<string, number>;
  onFocusCausalStep: (step: CausalChainStep) => void;
  onExportCsv: () => void;
  onExportAttractorCsv: () => void;
  attractorHistory: readonly PhasePoint[];
  attractorMode: AttractorMode;
}

const attractorLabels: Record<AttractorMode, string> = {
  stationary: 'Стационарный аттрактор',
  periodic: 'Периодический аттрактор',
  chaotic: 'Хаотический каскад',
  collapse: 'Точка коллапса',
};

export function Sidebar({ selected, devices, logs, causalChain, causalEmptyText, postings, spendByOwner, onFocusCausalStep, onExportCsv, onExportAttractorCsv, attractorHistory, attractorMode }: SidebarProps): JSX.Element {
  const [activeTab, setActiveTab] = useState<'details' | 'causal'>('details');
  const newestFirst = useMemo(() => [...logs].reverse(), [logs]);
  if (selected === undefined) {
    return <aside className="sidebar"><AttractorWidget mode={attractorMode} history={attractorHistory} points={attractorHistory.length} onExport={onExportAttractorCsv} /><button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button><CausalChainTracker steps={causalChain} emptyText={causalEmptyText} onFocus={onFocusCausalStep} /><SpendPanel postings={postings} spendByOwner={spendByOwner} /><p className="muted">Выберите дом или узел на карте.</p><LogList logs={newestFirst} /></aside>;
  }
  return (
    <aside className="sidebar">
      <nav className="sidebar-tabs"><button type="button" className={activeTab === 'details' ? 'active' : ''} onClick={() => setActiveTab('details')}>Сущность</button><button type="button" className={activeTab === 'causal' ? 'active' : ''} onClick={() => setActiveTab('causal')}>Логика каскада событий</button></nav>
      {activeTab === 'causal' ? <CausalChainTracker steps={causalChain} emptyText={causalEmptyText} onFocus={onFocusCausalStep} /> : <>
      <button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button>
      <AttractorWidget mode={attractorMode} history={attractorHistory} points={attractorHistory.length} onExport={onExportAttractorCsv} />
      <div className="sidebar-heading">
        <div><span className="eyebrow">{selected.type}</span><h2>{selected.id}</h2></div>
        <span className={`status status-${selected.status}`}>{selected.status}</span>
      </div>
      <p className="pid">PID процесса: <strong>{selected.pid ?? '—'}</strong></p>
      <section><h3>Метрики</h3><Metric label="Температура" value={selected.metrics.temperature} suffix=" °C" /><Metric label="Потребление" value={selected.metrics.power_consumption} suffix=" W" /><Metric label="Вода" value={selected.metrics.water_level} suffix=" %" /><Metric label="Жильцов" value={selected.metrics.occupants} digits={0} /><Metric label="Стресс" value={selected.metrics.stress} /><Metric label="Здоровье" value={selected.metrics.health} suffix=" hp" />
      {selected.metrics.spend !== undefined && <div className="metric"><span>Начислено</span><strong>{formatMoney(selected.metrics.spend)}</strong></div>}</section>
      <VmState state={selected.vmState} />
      {devices.length > 0 && <section><h3>Приборы</h3>{devices.map((device) => <div className="device" key={device.id}><strong>{device.type}</strong><span>PID {device.pid ?? '—'}</span><span>{device.metrics.power_consumption ?? 0} W</span></div>)}</section>}
      <LogList logs={newestFirst} />
      </>}
    </aside>
  );
}

function AttractorWidget({ mode, history, points, onExport }: { mode: AttractorMode; history: readonly PhasePoint[]; points: number; onExport: () => void }): JSX.Element {
  const [showVortex, setShowVortex] = useState(false);
  return <section className={`attractor-widget attractor-${mode}`}><div className="attractor-heading"><h3>Динамика системы (Аттрактор)</h3><span>{points} точек</span></div><strong>{attractorLabels[mode]}</strong><AttractorRadar history={history} mode={mode} /><button type="button" className="export-button" onClick={() => setShowVortex((visible) => !visible)}>{showVortex ? 'Скрыть 3D вихрь' : 'Открыть 3D вихрь'}</button>{showVortex && <AttractorVortex3D history={history} mode={mode} />}<button type="button" className="export-button" onClick={onExport}>Экспорт аттрактора в CSV</button></section>;
}

function Metric({ label, value, suffix = '', digits = 1 }: { label: string; value: number | undefined; suffix?: string; digits?: number }): JSX.Element {
  return <div className="metric"><span>{label}</span><strong>{value === undefined ? '—' : `${value.toFixed(digits)}${suffix}`}</strong></div>;
}

/** Behavior variables held by the entity's own VM; only observed runs provide them. */
function VmState({ state }: { state: Record<string, unknown> | undefined }): JSX.Element | null {
  if (state === undefined || Object.keys(state).length === 0) return null;
  return <section><h3>Состояние ВМ</h3>{Object.entries(state).map(([name, value]) => <div className="metric" key={name}><span>{name}</span><strong>{formatVmValue(value)}</strong></div>)}</section>;
}

function LogList({ logs }: { logs: readonly EntityLog[] }): JSX.Element {
  return <section className="logs"><h3>Журнал событий ({logs.length})</h3><FixedSizeList height={220} width="100%" itemCount={logs.length} itemSize={42} itemData={logs}>{({ index, style, data }) => <div style={style} className={`log log-${data[index].level}`}><time>{new Date(data[index].timestamp).toLocaleTimeString()}</time><span>{data[index].message}</span></div>}</FixedSizeList></section>;
}
