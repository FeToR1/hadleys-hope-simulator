import { FixedSizeList } from 'react-window';
import { useMemo, useState, type JSX } from 'react';
import type { EntityLog, EntityState, Posting } from '../domain/types';
import type { CausalChainStep } from '../domain/types';
import { formatVmValue } from '../domain/format';
import { CausalChainTracker } from './CausalChainTracker';
import { SpendPanel, formatMoney } from './SpendPanel';

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
}

export function Sidebar({ selected, devices, logs, causalChain, causalEmptyText, postings, spendByOwner, onFocusCausalStep, onExportCsv }: SidebarProps): JSX.Element {
  const [activeTab, setActiveTab] = useState<'details' | 'causal'>('details');
  const newestFirst = useMemo(() => [...logs].reverse(), [logs]);
  if (selected === undefined) {
    return <aside className="sidebar"><button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button><CausalChainTracker steps={causalChain} emptyText={causalEmptyText} onFocus={onFocusCausalStep} /><SpendPanel postings={postings} spendByOwner={spendByOwner} /><p className="muted">Выберите дом или узел на карте.</p><LogList logs={newestFirst} /></aside>;
  }
  return (
    <aside className="sidebar">
      <nav className="sidebar-tabs"><button type="button" className={activeTab === 'details' ? 'active' : ''} onClick={() => setActiveTab('details')}>Сущность</button><button type="button" className={activeTab === 'causal' ? 'active' : ''} onClick={() => setActiveTab('causal')}>Логика каскада событий</button></nav>
      {activeTab === 'causal' ? <CausalChainTracker steps={causalChain} emptyText={causalEmptyText} onFocus={onFocusCausalStep} /> : <>
      <button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button>
      <div className="sidebar-heading">
        <div><span className="eyebrow">{selected.type === 'mine' ? 'Место работы' : selected.type}</span><h2>{selected.type === 'mine' ? 'Шахта' : selected.id}</h2></div>
        <span className={`status status-${selected.status}`}>{selected.status}</span>
      </div>
      {selected.type === 'mine' ? <section className="mine-details"><h3>Рабочая смена</h3>
        <Metric label="Шахтёров на работе" value={selected.metrics.workers} digits={0} />
        <p className="muted">{(selected.metrics.workers ?? 0) > 0 ? 'Шахтёры прибыли и работают внутри шахты.' : 'Шахта ожидает следующую смену.'}</p>
      </section> : <>
      <p className="pid">PID процесса: <strong>{selected.pid ?? '—'}</strong></p>
      <section><h3>Метрики</h3><Metric label="Температура" value={selected.metrics.temperature} suffix=" °C" /><Metric label="Потребление" value={selected.metrics.power_consumption} suffix=" W" /><Metric label="Вода" value={selected.metrics.water_level} suffix=" %" /><Metric label="Жильцов" value={selected.metrics.occupants} digits={0} /><Metric label="Стресс" value={selected.metrics.stress} /><Metric label="Здоровье" value={selected.metrics.health} suffix=" hp" />
      {selected.metrics.spend !== undefined && <div className="metric"><span>Начислено</span><strong>{formatMoney(selected.metrics.spend)}</strong></div>}</section>
      <VmState state={selected.vmState} />
      </>}
      {selected.type === 'rover' && <section><h3>Перевозка</h3><Metric label="Пассажиры" value={selected.metrics.passenger_count} digits={0} /><Metric label="Вместимость" value={selected.metrics.passenger_capacity} digits={0} /></section>}
      {selected.type === 'marine' && <section><h3>Отряд</h3><Metric label="Живых бойцов" value={selected.metrics.squad_size} digits={0} /></section>}
      {devices.length > 0 && <section><h3>Приборы</h3>{devices.map((device) => <div className="device" key={device.id}><strong>{device.type}</strong><span>PID {device.pid ?? '—'}</span><span>{device.metrics.power_consumption ?? 0} W</span></div>)}</section>}
      <LogList logs={newestFirst} />
      </>}
    </aside>
  );
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
