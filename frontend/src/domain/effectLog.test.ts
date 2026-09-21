import { describe, expect, it } from 'vitest';
import { describeEvent, describeEvents } from './effectLog';
import { formatVmValue } from './format';
import type { TickBatch, WorldEvent } from './types';

const event = (partial: Partial<WorldEvent> & Pick<WorldEvent, 'type' | 'entityId'>): WorldEvent =>
  ({ id: 'e1', tick: 7, fields: {}, recipients: [], ...partial });
const batch = (events: WorldEvent[]): TickBatch => ({ tickId: 7, timestamp: 8000, entities: [], events });

describe('world event log', () => {
  it('reports attacks, breaks and deaths and skips the confirmation that only repeats the attack', () => {
    const drafts = describeEvents(batch([
      event({ type: 'ActionSucceeded', entityId: 'alien-1/xenomorph' }),
      event({ type: 'DamageApplied', entityId: 'home-1/heater', actorId: 'alien-1/xenomorph',
        fields: { target: 'home-1/heater', amount: 35, reason: 'XenomorphAttack' } }),
      event({ type: 'ObjectBroken', entityId: 'home-1/heater', fields: { object: 'home-1/heater', reason: 'XenomorphAttack' } }),
      event({ type: 'EntityDied', entityId: 'home-1/resident', fields: { entity: 'home-1/resident' } }),
    ]), 123);
    expect(drafts.map((draft) => draft.level)).toEqual(['warning', 'error', 'error']);
    expect(drafts[0].timestamp).toBe(123);
    expect(drafts[0].message).toContain('[тик 7]');
    expect(drafts[0].message).toContain('alien-1/xenomorph → урон home-1/heater: −35 hp (XenomorphAttack)');
    expect(drafts[1].message).toContain('сломан home-1/heater');
    expect(drafts[2].message).toContain('погиб home-1/resident');
  });

  it('marks rejected actions and reports losses and returns of power and water', () => {
    const rejected = describeEvent(event({ type: 'ActionRejected', entityId: 'a', fields: { action: 'damage', reason: 'executor_unable' } }));
    expect(rejected?.level).toBe('info');
    expect(rejected?.message).toContain('отклонено (executor_unable)');
    expect(describeEvent(event({ type: 'PowerLost', entityId: 'home-2/house' }))?.level).toBe('warning');
    expect(describeEvent(event({ type: 'PowerRestored', entityId: 'home-2/house' }))?.message).toContain('питание восстановлено');
    expect(describeEvent(event({ type: 'WaterLost', entityId: 'home-2/house' }))?.message).toContain('вода пропала');
  });

  it('still shows an event type it has no wording for', () => {
    expect(describeEvent(event({ type: 'RepairCompleted', entityId: 'pole-1' }))?.message).toContain('RepairCompleted pole-1');
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
