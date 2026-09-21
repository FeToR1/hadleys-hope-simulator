import type { EntityLog, TickBatch, WorldEvent } from './types';

export type LogDraft = Omit<EntityLog, 'id'>;

const text = (value: unknown): string => (typeof value === 'string' ? value : '?');
const amount = (value: unknown): string => (typeof value === 'number' && Number.isFinite(value) ? String(value) : '?');

/** One readable line for a world event, or undefined for events that only repeat another one. */
export function describeEvent(event: WorldEvent): Omit<LogDraft, 'timestamp'> | undefined {
  const prefix = `[тик ${event.tick}]`;
  switch (event.type) {
    case 'DamageApplied':
      return { entityId: event.entityId, level: 'warning',
        message: `${prefix} ${event.actorId ?? '?'} → урон ${text(event.fields.target)}: −${amount(event.fields.amount)} hp (${text(event.fields.reason)})` };
    case 'ObjectBroken':
      return { entityId: event.entityId, level: 'error', message: `${prefix} сломан ${text(event.fields.object)} (${text(event.fields.reason)})` };
    case 'EntityDied':
      return { entityId: event.entityId, level: 'error', message: `${prefix} погиб ${text(event.fields.entity)}` };
    case 'ActionRejected':
      return { entityId: event.entityId, level: 'info',
        message: `${prefix} ${event.entityId}: действие «${text(event.fields.action)}» отклонено (${text(event.fields.reason)})` };
    case 'PowerLost': return { entityId: event.entityId, level: 'warning', message: `${prefix} ${event.entityId}: питание потеряно` };
    case 'PowerRestored': return { entityId: event.entityId, level: 'info', message: `${prefix} ${event.entityId}: питание восстановлено` };
    case 'WaterLost': return { entityId: event.entityId, level: 'warning', message: `${prefix} ${event.entityId}: вода пропала` };
    case 'WaterRestored': return { entityId: event.entityId, level: 'info', message: `${prefix} ${event.entityId}: вода вернулась` };
    case 'ActionSucceeded': return undefined; // always accompanied by DamageApplied, which says more
    default: return { entityId: event.entityId, level: 'info', message: `${prefix} ${event.type} ${event.entityId}` };
  }
}

/** Log entries for the events of one step. Per-step power and motion requests are not events and stay out. */
export function describeEvents(batch: TickBatch, now: number): LogDraft[] {
  const drafts: LogDraft[] = [];
  for (const event of batch.events ?? []) {
    const draft = describeEvent(event);
    if (draft !== undefined) drafts.push({ ...draft, timestamp: now });
  }
  return drafts;
}
