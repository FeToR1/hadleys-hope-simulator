import { KNOWN_ENTITY_TYPES, type EntityType, type TickBatch, type TraceEffect, type WorldEvent } from './types';

export type DataSourceMode = 'mock' | 'live';

export type RunStatus = 'waiting' | 'running' | 'paused' | 'completed' | 'failed';

/** State of the observed run as reported by the gateway; undefined means the gateway is unreachable. */
export interface BrokerHealth {
  status: RunStatus;
  runId: string;
  /** Last committed step, -1 before the first one. */
  tick: number;
  ticks: number;
  stepsPerSecond: number;
  error?: string;
}

export type ControlAction = 'pause' | 'resume' | 'step' | 'reset' | { speed: number };

interface LiveSourceOptions {
  healthUrl: string;
  streamUrl: string;
  controlUrl: string;
  onHealthChange: (health: BrokerHealth | undefined) => void;
  onBatch: (batch: TickBatch) => void;
  onError: (message: string) => void;
}

export class LiveBrokerSource {
  private healthTimer: ReturnType<typeof setInterval> | undefined;
  private stream: EventSource | undefined;

  public constructor(private readonly options: LiveSourceOptions) {}

  public startHealthCheck(): () => void {
    let stopped = false;
    const check = async (): Promise<void> => {
      try {
        const response = await fetch(this.options.healthUrl, { method: 'GET', cache: 'no-store' });
        // 503 is a reachable gateway whose run failed; its body still says why.
        const health = response.ok || response.status === 503 ? parseHealth(await response.json()) : undefined;
        if (!stopped) this.options.onHealthChange(health);
      } catch {
        if (!stopped) this.options.onHealthChange(undefined);
      }
    };
    void check();
    this.healthTimer = setInterval(() => void check(), 2_000);
    return () => {
      stopped = true;
      if (this.healthTimer !== undefined) clearInterval(this.healthTimer);
      this.healthTimer = undefined;
    };
  }

  /** Pause, resume, single step, restart or speed change; the gateway answers with the new run state. */
  public async control(action: ControlAction): Promise<BrokerHealth | undefined> {
    const path = typeof action === 'string' ? action : `speed?value=${encodeURIComponent(String(action.speed))}`;
    try {
      const response = await fetch(`${this.options.controlUrl}/${path}`, { method: 'POST', cache: 'no-store' });
      const body: unknown = await response.json();
      const health = response.ok ? parseHealth(body) : undefined;
      if (health === undefined) {
        const reason = typeof body === 'object' && body !== null && 'error' in body ? String((body as { error: unknown }).error) : `HTTP ${response.status}`;
        this.options.onError(`Команда не выполнена: ${reason}`);
        return undefined;
      }
      this.options.onHealthChange(health);
      return health;
    } catch {
      this.options.onError('Команда не выполнена: шлюз недоступен');
      return undefined;
    }
  }

  public connect(): void {
    this.disconnect();
    if (typeof EventSource === 'undefined') {
      this.options.onError('Live Broker недоступен в текущем окружении');
      return;
    }
    this.stream = new EventSource(this.options.streamUrl);
    this.stream.onmessage = (event) => {
      try {
        this.options.onBatch(parseTickBatch(JSON.parse(event.data)));
      } catch {
        this.options.onError('Live Broker прислал некорректный TickBatch');
      }
    };
    this.stream.onerror = () => this.options.onError('Соединение с Live Broker потеряно');
  }

  public disconnect(): void {
    if (this.stream) { this.stream.onmessage = null; this.stream.onerror = null; }
    this.stream?.close();
    this.stream = undefined;
  }
}

/** Validate the health document; anything that does not look like one counts as an unreachable gateway. */
export function parseHealth(value: unknown): BrokerHealth | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return undefined;
  const item = value as Record<string, unknown>;
  const statuses: readonly unknown[] = ['waiting', 'running', 'paused', 'completed', 'failed'];
  if (!statuses.includes(item.status) || typeof item.runId !== 'string' || !Number.isSafeInteger(item.tick) ||
    !Number.isSafeInteger(item.ticks) || typeof item.stepsPerSecond !== 'number' || !Number.isFinite(item.stepsPerSecond) ||
    !(item.error === undefined || typeof item.error === 'string')) return undefined;
  return { status: item.status as RunStatus, runId: item.runId, tick: Number(item.tick), ticks: Number(item.ticks),
    stepsPerSecond: item.stepsPerSecond, error: item.error };
}

/** Validate the observer boundary before a payload can partially mutate application state. */
export function parseTickBatch(value: unknown): TickBatch {
  const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
  const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
  if (!record(value) || value.version !== 1 || typeof value.runId !== 'string' || !value.runId ||
    typeof value.seed !== 'string' || !['reference', 'process'].includes(String(value.runtimeMode)) ||
    value.full !== true || !Number.isSafeInteger(value.tickId) || Number(value.tickId) < 0 ||
    !finite(value.timestamp) || !Array.isArray(value.entities)) throw new Error('Invalid observer envelope');
  const ids = new Set<string>();
  for (const entity of value.entities) {
    if (!record(entity) || typeof entity.id !== 'string' || ids.has(entity.id) ||
      !(entity.pid === null || (Number.isSafeInteger(entity.pid) && Number(entity.pid) > 0)) ||
      typeof entity.type !== 'string' || entity.type === '' ||
      !['nominal', 'warning', 'critical', 'dead'].includes(String(entity.status)) ||
      !record(entity.metrics) || !Object.values(entity.metrics).every(finite) ||
      !Array.isArray(entity.connectedTo) || !entity.connectedTo.every((id) => typeof id === 'string') ||
      !record(entity.coordinates) || !finite(entity.coordinates.x) || !finite(entity.coordinates.y) ||
      !(entity.parentId === undefined || entity.parentId === null || typeof entity.parentId === 'string') ||
      !(entity.vmState === undefined || record(entity.vmState))) throw new Error('Invalid entity');
    ids.add(entity.id);
  }
  const effects = parseEffects(value.effects);
  const events = parseEvents(value.events);
  // Kotlin JSON emits explicit nulls; the UI uses undefined for absent parents.
  // A kind this UI does not know yet is kept and drawn as a generic object.
  return {
    ...value,
    effects,
    events,
    entities: value.entities.map((entity) => ({
      ...entity,
      type: (KNOWN_ENTITY_TYPES as readonly string[]).includes(String(entity.type)) ? entity.type : 'other' as EntityType,
      parentId: entity.parentId ?? undefined,
    })),
  } as unknown as TickBatch;
}

function parseEvents(value: unknown): WorldEvent[] {
  if (value === undefined) return [];
  if (!Array.isArray(value)) throw new Error('Invalid events');
  const optionalText = (v: unknown): boolean => v === undefined || v === null || typeof v === 'string';
  return value.map((event: unknown) => {
    if (typeof event !== 'object' || event === null || Array.isArray(event)) throw new Error('Invalid event');
    const item = event as Record<string, unknown>;
    const fields = item.fields ?? {};
    if (typeof item.id !== 'string' || typeof item.type !== 'string' || !Number.isSafeInteger(item.tick) ||
      typeof item.entityId !== 'string' || !optionalText(item.actorId) || !optionalText(item.causationId) ||
      typeof fields !== 'object' || fields === null || Array.isArray(fields) ||
      !Array.isArray(item.recipients) || !item.recipients.every((id) => typeof id === 'string')) throw new Error('Invalid event');
    return { id: item.id, type: item.type, tick: Number(item.tick), entityId: item.entityId, actorId: (item.actorId as string | null) ?? undefined,
      causationId: (item.causationId as string | null) ?? undefined, fields: fields as Record<string, unknown>, recipients: item.recipients as string[] };
  });
}

function parseEffects(value: unknown): TraceEffect[] {
  if (value === undefined) return [];
  if (!Array.isArray(value)) throw new Error('Invalid effects');
  return value.map((effect: unknown) => {
    if (typeof effect !== 'object' || effect === null || Array.isArray(effect)) throw new Error('Invalid effect');
    const item = effect as Record<string, unknown>;
    if (typeof item.source !== 'string' || typeof item.operation !== 'string' || !Array.isArray(item.arguments) ||
      !(item.accepted === undefined || typeof item.accepted === 'boolean')) throw new Error('Invalid effect');
    return { source: item.source, operation: item.operation, arguments: item.arguments, accepted: item.accepted ?? true };
  });
}
