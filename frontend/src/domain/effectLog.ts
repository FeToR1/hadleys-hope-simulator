import type { EntityLog, EntityStatus, TickBatch } from './types';

export type LogDraft = Omit<EntityLog, 'id'>;

const asNumber = (value: unknown): number | undefined => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);

/**
 * Notable requests of one step as log entries. Power and motion requests are made every step
 * by every device and agent, so they stay out of the log; attacks and repairs are events worth reading.
 */
export function describeEffects(batch: TickBatch, now: number): LogDraft[] {
  const drafts: LogDraft[] = [];
  for (const effect of batch.effects ?? []) {
    if (effect.operation !== 'DAMAGE_REQUEST' && effect.operation !== 'REPAIR_REQUEST') continue;
    const target = String(effect.arguments[0] ?? '');
    const what = effect.operation === 'DAMAGE_REQUEST'
      ? `урон ${target}: −${asNumber(effect.arguments[1]) ?? '?'} hp${effect.arguments[2] === undefined ? '' : ` (${String(effect.arguments[2])})`}`
      : `ремонт ${target}`;
    drafts.push({
      timestamp: now,
      entityId: target,
      level: effect.accepted ? 'warning' : 'info',
      message: `[тик ${batch.tickId}] ${effect.source}: ${what}${effect.accepted ? '' : ' — отклонено, исполнитель не может действовать'}`,
    });
  }
  return drafts;
}

export function describeStatusChange(entityId: string, from: EntityStatus, to: EntityStatus, tickId: number, now: number): LogDraft {
  return {
    timestamp: now,
    entityId,
    level: to === 'dead' ? 'error' : to === 'nominal' ? 'info' : 'warning',
    message: `[тик ${tickId}] ${entityId}: ${from} → ${to}`,
  };
}
