import { describe, expect, it } from 'vitest';
import { createLiveCausalChain } from './liveCausalChain';
import type { WorldEvent } from './types';

const event = (id: string, type: string, entityId: string, extra: Partial<WorldEvent> = {}): WorldEvent =>
  ({ id, type, tick: 3, entityId, fields: {}, recipients: [], ...extra });

describe('live causal chain', () => {
  it('is empty until something breaks or dies', () => {
    expect(createLiveCausalChain([])).toEqual([]);
    expect(createLiveCausalChain([event('e1', 'DamageApplied', 'a')])).toEqual([]);
  });

  it('walks the causation links from the latest break back to the attack, root first', () => {
    const chain = createLiveCausalChain([
      event('e1', 'DamageApplied', 'home-1/heater', { actorId: 'alien-1/xenomorph', causationId: 'alien-1/xenomorph@2#0', fields: { amount: 60, reason: 'Bite' } }),
      event('e2', 'ObjectBroken', 'home-1/heater', { causationId: 'e1', fields: { reason: 'Bite' } }),
    ]);
    expect(chain.map((step) => step.kind)).toEqual(['action', 'failure']);
    expect(chain[0].title).toBe('alien-1/xenomorph атаковал home-1/heater');
    expect(chain[0].detail).toContain('−60 hp (Bite)');
    expect(chain[1].title).toBe('Сломан home-1/heater');
    expect(chain.every((step) => step.focus === 'map' && step.focusEntityId === 'home-1/heater')).toBe(true);
  });

  it('follows the newest terminal event and survives cycles and missing causes', () => {
    const chain = createLiveCausalChain([
      event('e1', 'ObjectBroken', 'old', { causationId: 'gone' }),
      event('e2', 'EntityDied', 'new', { causationId: 'e3' }),
      event('e3', 'DamageApplied', 'new', { causationId: 'e2' }),
    ]);
    expect(chain.map((step) => step.id)).toEqual(['e3', 'e2']);
    expect(chain[1].title).toBe('Погиб new');
  });
});
