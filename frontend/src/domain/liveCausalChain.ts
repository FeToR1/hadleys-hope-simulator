import type { CausalChainStep, WorldEvent } from './types';

const TERMINAL = new Set(['ObjectBroken', 'EntityDied']);

function step(event: WorldEvent): CausalChainStep {
  const focus = { focus: 'map' as const, focusEntityId: event.entityId };
  switch (event.type) {
    case 'DamageApplied':
      return { id: event.id, kind: 'action', title: `${event.actorId ?? '?'} атаковал ${event.entityId}`,
        detail: `−${String(event.fields.amount)} hp (${String(event.fields.reason)}) · тик ${event.tick}`, ...focus };
    case 'ObjectBroken':
      return { id: event.id, kind: 'failure', title: `Сломан ${event.entityId}`, detail: `причина: ${String(event.fields.reason)} · тик ${event.tick}`, ...focus };
    case 'EntityDied':
      return { id: event.id, kind: 'failure', title: `Погиб ${event.entityId}`, detail: `тик ${event.tick}`, ...focus };
    default:
      return { id: event.id, kind: 'network', title: `${event.type} ${event.entityId}`, detail: `тик ${event.tick}`, ...focus };
  }
}

/**
 * The chain of causes behind the latest broken object or death, root first, built from the causation links
 * the world reports. The first link is a request of a program, which is not an event, so the chain starts at
 * the event that request produced.
 */
export function createLiveCausalChain(events: readonly WorldEvent[]): CausalChainStep[] {
  const byId = new Map(events.map((event) => [event.id, event]));
  const last = [...events].reverse().find((event) => TERMINAL.has(event.type));
  if (last === undefined) return [];
  const chain: WorldEvent[] = [];
  const seen = new Set<string>();
  for (let current: WorldEvent | undefined = last; current !== undefined && !seen.has(current.id);
    current = current.causationId === undefined ? undefined : byId.get(current.causationId)) {
    seen.add(current.id);
    chain.unshift(current);
  }
  return chain.map(step);
}
