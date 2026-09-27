import { useEffect, useMemo, useRef, useState, type JSX } from 'react';
import type { EntityState } from '../domain/types';
import { connectionMatches, createNetworkOverview, fixtureKind, networkLabel, type NetworkKind, type HouseConnection } from '../domain/networkOverview';

interface NetworkTopologyProps {
  entities: readonly EntityState[];
  onSelect: (entityId: string) => void;
  selectedId?: string;
  onRegisterFocus?: (focus: (entityId: string) => void) => void;
}

const PAGE_SIZE = 30;
const statusText = { nominal: 'Работает', warning: 'Нет питания', critical: 'Авария', dead: 'Сломан' };

export function NetworkTopology({ entities, onSelect, selectedId, onRegisterFocus }: NetworkTopologyProps): JSX.Element {
  const [network, setNetwork] = useState<NetworkKind>('power');
  const [groupId, setGroupId] = useState<string>();
  const [query, setQuery] = useState('');
  const [problemsOnly, setProblemsOnly] = useState(false);
  const [page, setPage] = useState(0);
  const scrollRef = useRef<HTMLDivElement>(null);
  const current = useRef({ entities, network });
  current.current = { entities, network };
  // Mount only the current section; no iterative layout or asynchronous draw queue.
  const overview = useMemo(() => createNetworkOverview(entities, network), [entities, network]);
  const focus = useRef((id: string) => {
    const { entities: latest, network: layer } = current.current;
    const entity = latest.find((item) => item.id === id);
    const targetLayer = entity && ['pipe', 'pump'].includes(fixtureKind(entity)) ? 'water'
      : entity?.type === 'power_node' ? 'power' : layer;
    const data = createNetworkOverview(latest, targetLayer);
    const group = data.equipment.some((item) => item.id === id) ? undefined : data.groups.find((item) => item.connections.some((connection) =>
      connection.house.id === id || connection.path.some((node) => node.id === id)));
    setNetwork(targetLayer);
    setGroupId(group?.id);
    setQuery('');
    setProblemsOnly(false);
    const index = group?.connections.findIndex((connection) => connection.house.id === id || connection.path.some((node) => node.id === id)) ?? 0;
    setPage(Math.max(0, Math.floor(index / PAGE_SIZE)));
  });
  useEffect(() => { onRegisterFocus?.(focus.current); }, [onRegisterFocus]);
  useEffect(() => { if (selectedId) focus.current(selectedId); }, [selectedId]);
  useEffect(() => { if (scrollRef.current) scrollRef.current.scrollTop = 0; }, [network, groupId, page, query, problemsOnly]);

  const active = overview.groups.find((group) => group.id === groupId);
  const matches = (connection: HouseConnection): boolean =>
    (!problemsOnly || connection.problem) && connectionMatches(connection, query);
  const visibleGroups = overview.groups.filter((group) => group.connections.some(matches));
  const connections = active?.connections.filter(matches) ?? [];
  const pageCount = Math.max(1, Math.ceil(connections.length / PAGE_SIZE));
  const actualPage = Math.min(page, pageCount - 1);
  const chooseNetwork = (next: NetworkKind): void => { setNetwork(next); setGroupId(undefined); setPage(0); };
  const inspect = (entity: EntityState): JSX.Element => (
    <button type="button" className={`network-entity ${selectedId === entity.id ? 'is-selected' : ''}`}
      onClick={() => onSelect(entity.id)} title={entity.id}>
      <span className={`network-dot status-${entity.status}`} />{networkLabel(entity)}
    </button>
  );

  return (
    <div className="visualization-shell network-panel">
      <div className="network-toolbar">
        <div className="network-switch" aria-label="Вид сети">
          <button type="button" aria-pressed={network === 'power'} onClick={() => chooseNetwork('power')}>Электричество</button>
          <button type="button" aria-pressed={network === 'water'} onClick={() => chooseNetwork('water')}>Вода</button>
        </div>
        <input aria-label="Найти дом или узел" placeholder="Найти дом или узел…" value={query}
          onChange={(event) => { setQuery(event.target.value); setPage(0); }} />
        <label className="network-filter"><input type="checkbox" checked={problemsOnly}
          onChange={(event) => { setProblemsOnly(event.target.checked); setPage(0); }} />Только проблемы</label>
      </div>
      <div className="network-summary" aria-live="polite">
        <span><strong>{overview.houseCount}</strong> домов</span>
        <span className={overview.problemCount ? 'network-alert' : ''}><strong>{overview.problemCount}</strong> {network === 'power' ? 'с проблемами питания' : 'с проблемами воды'}</span>
        {overview.unknownCount > 0 && <span>{overview.unknownCount} без данных о подключении</span>}
      </div>
      <div className="network-scroll" ref={scrollRef}>
        {active ? <>
          <button type="button" className="network-back" onClick={() => { setGroupId(undefined); setPage(0); }}>← Все участки</button>
          <div className="network-section-heading"><h2>{active.label}</h2><span>{active.connections.length} домов · найдено {connections.length}</span></div>
          <p className="network-help">Нажмите на узел или дом, чтобы открыть его состояние.</p>
          <div className="network-connections">
            {connections.slice(actualPage * PAGE_SIZE, (actualPage + 1) * PAGE_SIZE).map((connection) => (
              <div key={connection.house.id} className={`network-connection ${connection.problem ? 'has-problem' : ''}`}>
                <div className="network-route">
                  {connection.path.map((node) => <span className="network-hop" key={node.id}>{inspect(node)}<span aria-hidden="true">→</span></span>)}
                  {inspect(connection.house)}
                </div>
                <span className={`network-connection-status ${connection.problem ? 'network-alert' : ''}`}>
                  {connection.problem ? (network === 'water' ? 'Проблема с водой' : 'Проблема с питанием') : connection.unknown ? 'Связь не указана' : 'Без сбоев на участке'}
                </span>
              </div>
            ))}
          </div>
          {connections.length === 0 && <p className="muted">Нет домов, подходящих под фильтр.</p>}
          {pageCount > 1 && <div className="network-pagination">
            <button type="button" disabled={actualPage === 0} onClick={() => setPage(actualPage - 1)}>Назад</button>
            <span>{actualPage + 1} / {pageCount}</span>
            <button type="button" disabled={actualPage + 1 === pageCount} onClick={() => setPage(actualPage + 1)}>Далее</button>
          </div>}
        </> : <>
          {overview.equipment.length > 0 && <>
            <h2>Оборудование сети</h2>
            <div className="network-equipment">{overview.equipment.map((entity) => (
              <div key={entity.id} className="network-equipment-card">{inspect(entity)}<small>{statusText[entity.status]}</small></div>
            ))}</div>
          </>}
          <div className="network-section-heading"><h2>{network === 'power' ? 'Распределительные линии' : 'Подключения воды'}</h2><span>{visibleGroups.length} участков</span></div>
          <p className="network-help">{network === 'power'
            ? 'Откройте линию, чтобы увидеть подключённые дома и место сбоя.'
            : 'Трубы сгруппированы по 20 домов для просмотра. Внутри — подключения каждого дома.'}</p>
          <div className="network-groups">{visibleGroups.map((group) => {
            const affected = group.connections.filter((connection) => connection.problem).length;
            const unknown = group.connections.filter((connection) => connection.unknown).length;
            return <button type="button" key={group.id} className={`network-group ${affected ? 'has-problem' : ''}`}
              onClick={() => { setGroupId(group.id); setPage(0); }}>
              <strong>{group.label}<span aria-hidden="true">↗</span></strong>
              <span>{group.connections.length} домов</span>
              <div className="network-meter" aria-hidden="true"><i style={{ width: `${100 * affected / group.connections.length}%` }} /></div>
              <small className={affected ? 'network-alert' : ''}>{affected ? `${affected} с проблемами` : unknown ? `${unknown} без данных о связи` : 'Сбоев не обнаружено'}</small>
            </button>;
          })}</div>
          {visibleGroups.length === 0 && <p className="muted">{entities.length ? 'Нет участков, подходящих под фильтр.' : 'Ожидаем данные о сети…'}</p>}
        </>}
      </div>
    </div>
  );
}
