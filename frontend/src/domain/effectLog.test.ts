import { describe, expect, it } from 'vitest';
import { describeEffects, describeStatusChange } from './effectLog';
import { formatVmValue } from './format';
import type { TickBatch } from './types';

const batch = (effects: NonNullable<TickBatch['effects']>): TickBatch => ({ tickId: 7, timestamp: 8000, entities: [], effects });

describe('effect log', () => {
  it('reports attacks and skips per-step power and motion requests', () => {
    const drafts = describeEffects(batch([
      { source: 'alien-1/xenomorph', operation: 'DAMAGE_REQUEST', arguments: ['home-1/heater', 35, 'XenomorphAttack'], accepted: true },
      { source: 'home-1/heater', operation: 'POWER_REQUEST', arguments: [2000], accepted: true },
      { source: 'alien-1/xenomorph', operation: 'MOTION_REQUEST', arguments: [{ x: 1, y: 2 }, 5], accepted: true },
    ]), 123);
    expect(drafts).toHaveLength(1);
    expect(drafts[0]).toMatchObject({ entityId: 'home-1/heater', level: 'warning', timestamp: 123 });
    expect(drafts[0].message).toContain('[тик 7]');
    expect(drafts[0].message).toContain('−35 hp (XenomorphAttack)');
  });

  it('marks rejected requests instead of hiding them', () => {
    const [draft] = describeEffects(batch([
      { source: 'a', operation: 'DAMAGE_REQUEST', arguments: ['b', 5, 'x'], accepted: false },
    ]), 0);
    expect(draft.level).toBe('info');
    expect(draft.message).toContain('отклонено');
  });

  it('classifies status changes by severity', () => {
    expect(describeStatusChange('e', 'nominal', 'dead', 3, 0).level).toBe('error');
    expect(describeStatusChange('e', 'nominal', 'warning', 3, 0).level).toBe('warning');
    expect(describeStatusChange('e', 'critical', 'nominal', 3, 0).level).toBe('info');
  });
});

describe('VM state formatting', () => {
  it('prints numbers, enums, Option and records readably', () => {
    expect(formatVmValue(2)).toBe('2');
    expect(formatVmValue(0.28)).toBe('0.280');
    expect(formatVmValue('Calm')).toBe('Calm');
    expect(formatVmValue(true)).toBe('true');
    expect(formatVmValue(null)).toBe('none');
    expect(formatVmValue({ some: true })).toBe('some(true)');
    expect(formatVmValue({ x: 1, y: 2 })).toBe('{"x":1,"y":2}');
  });
});
