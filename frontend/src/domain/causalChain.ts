import type { CausalChainStep, EntityState } from './types';

export function createCausalChain(entities: readonly EntityState[]): CausalChainStep[] {
  const power = entities.find((entity) => entity.id === 'power-1' && entity.status === 'dead');
  if (power === undefined) return [];
  const house = entities.find((entity) => entity.id === 'house-5');
  if (house === undefined) return [];
  const temperature = house.metrics.temperature ?? 0;
  return [
    { id: 'network-failure', kind: 'network', title: 'Авария на узле питания', detail: `power-1 · PID ${power.pid}`, focus: 'graph', focusEntityId: power.id },
    { id: 'thermal-failure', kind: 'thermal', title: 'Прекращение подачи энергии', detail: `Падение температуры в ${house.id} · текущая: ${temperature.toFixed(1)}°C`, focus: 'map', focusEntityId: house.id },
    { id: 'water-failure', kind: 'hydraulics', title: 'Температура ниже критической', detail: `Повреждение водопровода ${house.id}`, focus: 'map', focusEntityId: house.id },
    { id: 'repair-cost', kind: 'economy', title: 'Сформирован расход на ремонт', detail: `Владелец ${house.id} · -$500`, focus: 'map', focusEntityId: house.id },
  ];
}
