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
  incomeByOwner: ReadonlyMap<string, number>;
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

export function Sidebar({ selected, devices, logs, causalChain, causalEmptyText, postings, spendByOwner, incomeByOwner, onFocusCausalStep, onExportCsv, onExportAttractorCsv, attractorHistory, attractorMode }: SidebarProps): JSX.Element {
  const [activeTab, setActiveTab] = useState<'details' | 'causal'>('details');
  const newestFirst = useMemo(() => [...logs].reverse(), [logs]);
  if (selected === undefined) {
    return <aside className="sidebar"><AttractorWidget mode={attractorMode} history={attractorHistory} points={attractorHistory.length} onExport={onExportAttractorCsv} /><button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button><CausalChainTracker steps={causalChain} emptyText={causalEmptyText} onFocus={onFocusCausalStep} /><SpendPanel postings={postings} spendByOwner={spendByOwner} incomeByOwner={incomeByOwner} /><p className="muted">Выберите дом или узел на карте.</p><LogList logs={newestFirst} /></aside>;
  }
  return (
    <aside className="sidebar">
      <nav className="sidebar-tabs"><button type="button" className={activeTab === 'details' ? 'active' : ''} onClick={() => setActiveTab('details')}>Сущность</button><button type="button" className={activeTab === 'causal' ? 'active' : ''} onClick={() => setActiveTab('causal')}>Логика каскада событий</button></nav>
      {activeTab === 'causal' ? <CausalChainTracker steps={causalChain} emptyText={causalEmptyText} onFocus={onFocusCausalStep} /> : <>
      <button type="button" className="export-button" onClick={onExportCsv}>Экспорт отчета в CSV</button>
      <AttractorWidget mode={attractorMode} history={attractorHistory} points={attractorHistory.length} onExport={onExportAttractorCsv} />
      <div className="sidebar-heading">
        <div><span className="eyebrow">{selected.type === 'mine' ? 'Добыча креатина' : selected.type === 'air_defense' ? 'Оборона' : selected.type === 'depository' ? 'Хранилище' : selected.type === 'medical_center' ? 'Медицина' : selected.type === 'rover' ? roverRoleName(selected.id) : selected.type}</span><h2>{selected.type === 'mine' ? 'Шахта' : selected.type === 'air_defense' ? 'Система ПВО' : selected.type === 'depository' ? 'Склад креатина' : selected.type === 'medical_center' ? 'Медицинский центр' : selected.type === 'rover' ? roverRoleName(selected.id) : selected.id}</h2></div>
        <span className={`status status-${selected.status}`}>{selected.status}</span>
      </div>
      {(selected.type === 'air_defense' || selected.type === 'ground_turret') && <section className="defense-details"><h3>Оборона</h3>
        <Metric label="Боезапас" value={selected.metrics.ammo_remaining ?? selected.metrics.ammo} digits={0} />
        <Metric label="Выстрелов произведено" value={selected.metrics.shots_fired} digits={0} />
        {selected.metrics.refusal_reason && <div className="metric"><span>Причина отказа</span><strong>{defenseReasonLabel(selected.metrics.refusal_reason)}</strong></div>}
        <div className="metric"><span>Питание</span><strong>{selected.metrics.power_connected === false ? 'Нет' : selected.metrics.power_connected === true ? 'Подключено' : '—'}</strong></div>
      </section>}
      {selected.type === 'burner' && selected.metrics.shared_fuel_remaining !== undefined && <section><h3>Очистка планктона</h3>
        <Metric label="Общий запас топлива" value={selected.metrics.shared_fuel_remaining} suffix=" ед." />
      </section>}
      {selected.type === 'civilian' && selected.metrics.available === false && <p className="status status-warning">Житель временно недоступен</p>}
      {(selected.type === 'xenomorph' || selected.type === 'predator') && selected.metrics.mutation && <section><h3>Мутация</h3><p>{mutationLabel(selected.metrics.mutation)}</p></section>}
      {selected.type === 'mine' ? <section className="mine-details"><h3>Рабочая смена (Добыча креатина)</h3>
        <Metric label="Шахтёров на работе" value={selected.metrics.workers} digits={0} />
        <p className="muted">{(selected.metrics.workers ?? 0) > 0 ? 'Шахтёры прибыли и добывают ценный креатин.' : 'Шахта ожидает следующую смену.'}</p>
      </section> : selected.type === 'air_defense' ? <section className="ads-details"><h3>Система ПВО</h3>
        <p><strong>{selected.metrics.broken ? 'ОТКЛЮЧЕНА (Морской туман)' : 'Боеготовность / Сканирование'}</strong></p>
        {selected.metrics.in_fog && <p className="status status-warning">Находится в зоне морского испарения</p>}
        <Metric label="Прочность" value={selected.metrics.health} suffix=" hp" />
      </section> : selected.type === 'crocodile' ? <section className="croc-details"><h3>Летающий крокодил</h3>
        <p className="status status-critical">Воздушная угроза из леса</p>
        <Metric label="Здоровье" value={selected.metrics.health} suffix=" hp" />
      </section> : selected.type === 'depository' ? <section className="depository-details"><h3>Склад креатина</h3>
        <Metric label="Запас креатина" value={selected.metrics.creatine_stock} digits={0} suffix=" ед." />
        <p className="muted">Хранилище добытого креатина для продажи и поддержания поселка.</p>
      </section> : selected.type === 'medical_center' ? <section className="medcenter-details"><h3>Медицинский центр</h3>
        <Metric label="Запас креатина" value={selected.metrics.creatine_stock} digits={0} suffix=" ед." />
        <p className="muted">Использует креатин для исцеления раненых жителей и шахтёров.</p>
      </section> : selected.type === 'ecology_zone' ? <section><h3>Показатели зоны</h3>
        <div className="metric"><span>Границы X</span><strong>{selected.metrics.min_x?.toFixed(1) ?? '—'} … {selected.metrics.max_x?.toFixed(1) ?? '—'}</strong></div>
        <div className="metric"><span>Границы Y</span><strong>{selected.metrics.min_y?.toFixed(1) ?? '—'} … {selected.metrics.max_y?.toFixed(1) ?? '—'}</strong></div>
        <Metric label="Биомасса леса" value={selected.metrics.forest_biomass} />
        <Metric label="Навоз" value={selected.metrics.manure} />
        <Metric label="Биомасса планктона" value={selected.metrics.plankton_biomass} />
      </section> : <>
      <p className="pid">PID процесса: <strong>{selected.pid ?? '—'}</strong></p>
      <section><h3>Метрики</h3><Metric label="Температура" value={selected.metrics.temperature} suffix=" °C" /><Metric label="Потребление" value={selected.metrics.power_consumption} suffix=" W" /><Metric label="Вода" value={selected.metrics.water_level} suffix=" %" /><Metric label="Жильцов" value={selected.metrics.occupants} digits={0} /><Metric label="Стресс" value={selected.metrics.stress} /><Metric label="Здоровье" value={selected.metrics.health} suffix=" hp" />
      {selected.metrics.sea_damage !== undefined && selected.metrics.sea_damage > 0 && <Metric label="Урон от моря" value={selected.metrics.sea_damage} suffix=" dmg" />}
      {selected.metrics.in_fog && <div className="metric"><span>Туман</span><strong style={{ color: '#f2c94c' }}>В тумане (респиратор: {selected.metrics.respirator_equipped ? 'надет' : 'нет'})</strong></div>}
      {selected.metrics.spend !== undefined && <div className="metric"><span>Начислено</span><strong>{formatMoney(selected.metrics.spend)}</strong></div>}</section>
      <VmState state={selected.vmState} />
      </>}
      {selected.type === 'rover' && selected.metrics.passenger_capacity !== undefined && <section><h3>Перевозка</h3><Metric label="Пассажиры" value={selected.metrics.passenger_count} digits={0} /><Metric label="Вместимость" value={selected.metrics.passenger_capacity} digits={0} /></section>}
      {selected.type === 'marine' && <section><h3>Отряд</h3><Metric label="Живых бойцов" value={selected.metrics.squad_size} digits={0} /></section>}
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

function roverRoleName(id: string): string {
  if (id.startsWith('cleanup-')) return 'Уборочный ровер';
  if (id.startsWith('forester-')) return 'Лесной ровер';
  if (id.startsWith('crew-')) return 'Ремонтный ровер';
  if (id.startsWith('cargo-')) return 'Грузовой ровер';
  if (id.startsWith('transport-')) return 'Пассажирский ровер';
  return 'Ровер';
}

function defenseReasonLabel(reason: NonNullable<EntityState['metrics']['refusal_reason']>): string {
  return ({ broken: 'Повреждена', no_power: 'Нет питания', no_ammo: 'Нет боеприпасов', cooldown: 'Перезарядка' })[reason];
}

function mutationLabel(mutation: NonNullable<EntityState['metrics']['mutation']>): string {
  return ({ armored: 'Бронированный', swift: 'Быстрый', venomous: 'Ядовитый', pack: 'Степной', baseline: 'Обычный' })[mutation];
}

/** Behavior variables held by the entity's own VM; only observed runs provide them. */
function VmState({ state }: { state: Record<string, unknown> | undefined }): JSX.Element | null {
  if (state === undefined || Object.keys(state).length === 0) return null;
  return <section><h3>Состояние ВМ</h3>{Object.entries(state).map(([name, value]) => <div className="metric" key={name}><span>{name}</span><strong>{formatVmValue(value)}</strong></div>)}</section>;
}

function LogList({ logs }: { logs: readonly EntityLog[] }): JSX.Element {
  return <section className="logs"><h3>Журнал событий ({logs.length})</h3><FixedSizeList height={220} width="100%" itemCount={logs.length} itemSize={42} itemData={logs}>{({ index, style, data }) => <div style={style} className={`log log-${data[index].level}`}><time>{new Date(data[index].timestamp).toLocaleTimeString()}</time><span>{data[index].message}</span></div>}</FixedSizeList></section>;
}
