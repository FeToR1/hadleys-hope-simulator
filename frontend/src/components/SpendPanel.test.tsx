// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SpendPanel } from './SpendPanel';

describe('SpendPanel', () => {
  it('shows sales as income and calculates the net balance separately from expenses', () => {
    const html = renderToStaticMarkup(<SpendPanel postings={[
      { tick: 1, owner: 'home-1', kind: 'electricity', amount: 100 },
      { tick: 1, owner: 'colony', kind: 'creatine_sale', amount: 250 },
    ]} spendByOwner={new Map([['home-1', 100]])} incomeByOwner={new Map([['colony', 250]])} />);
    expect(html).toContain('Всего начислено');
    expect(html).toContain('Доход от продажи креатина');
    expect(html).toContain('Баланс');
    expect(html).toContain('150');
    expect(html).not.toContain('продажа креатина (доход)</span><strong>250');
  });
});
