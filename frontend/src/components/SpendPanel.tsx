import { useMemo, type JSX } from 'react';
import type { Posting } from '../domain/types';

interface SpendPanelProps {
  postings: readonly Posting[];
  spendByOwner: ReadonlyMap<string, number>;
  incomeByOwner: ReadonlyMap<string, number>;
}

const KIND_LABEL: Record<string, string> = {
  electricity: 'электричество',
  water: 'вода',
  repair: 'ремонт',
  creatine_sale: 'продажа креатина (доход)',
  defense: 'оборона и боеприпасы', defense_ammo: 'Боеприпасы ПВО', burn_electricity: 'Электроэнергия для сжигания', cleanup: 'уборка улиц', burn: 'выжигание планктона', respirator: 'респираторы',
};

/** Money in minimal units; the settlement keeps whole units, so this only groups the digits. */
export function formatMoney(amount: number): string {
  return amount.toLocaleString('ru-RU');
}

/**
 * What the settlement has been charged: the total, the split by kind, and the largest payers.
 * The world posts these lines; the dashboard only adds them up.
 */
export function SpendPanel({ postings, spendByOwner, incomeByOwner }: SpendPanelProps): JSX.Element {
  const summary = useMemo(() => {
    const byKind = new Map<string, number>();
    for (const posting of postings) if (posting.kind !== 'creatine_sale') byKind.set(posting.kind, (byKind.get(posting.kind) ?? 0) + posting.amount);
    const total = [...spendByOwner.values()].reduce((sum, value) => sum + value, 0);
    const owners = [...spendByOwner.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5);
    const income = [...incomeByOwner.values()].reduce((sum, value) => sum + value, 0);
    return { byKind: [...byKind.entries()].sort((a, b) => b[1] - a[1]), total, owners, income };
  }, [postings, spendByOwner, incomeByOwner]);

  if (summary.total === 0 && summary.income === 0) {
    return <section className="spend"><h3>Расходы</h3><p className="muted">Проводок пока нет.</p></section>;
  }
  return <section className="spend">
    <h3>Расходы</h3>
    <div className="metric"><span>Всего начислено</span><strong>{formatMoney(summary.total)}</strong></div>
    {summary.income > 0 && <><div className="metric"><span>Доход от продажи креатина</span><strong>{formatMoney(summary.income)}</strong></div><div className="metric"><span>Баланс</span><strong>{formatMoney(summary.income - summary.total)}</strong></div></>}
    {summary.byKind.map(([kind, amount]) => (
      <div className="metric" key={kind}><span>{KIND_LABEL[kind] ?? kind}</span><strong>{formatMoney(amount)}</strong></div>
    ))}
    <h3 className="spend-owners">Кто платит больше</h3>
    {summary.owners.map(([owner, amount]) => (
      <div className="metric" key={owner}><span>{owner}</span><strong>{formatMoney(amount)}</strong></div>
    ))}
  </section>;
}
