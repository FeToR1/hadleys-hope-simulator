import type { TickBatch } from './types';

export type DataSourceMode = 'mock' | 'live';

interface LiveSourceOptions {
  healthUrl: string;
  streamUrl: string;
  onHealthChange: (available: boolean) => void;
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
        if (!stopped) this.options.onHealthChange(response.ok);
      } catch {
        if (!stopped) this.options.onHealthChange(false);
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
      !['house', 'heater', 'kettle', 'civilian', 'xenomorph', 'power_node'].includes(String(entity.type)) ||
      !['nominal', 'warning', 'critical', 'dead'].includes(String(entity.status)) ||
      !record(entity.metrics) || !Object.values(entity.metrics).every(finite) ||
      !Array.isArray(entity.connectedTo) || !entity.connectedTo.every((id) => typeof id === 'string') ||
      !record(entity.coordinates) || !finite(entity.coordinates.x) || !finite(entity.coordinates.y) ||
      !(entity.parentId === undefined || entity.parentId === null || typeof entity.parentId === 'string')) throw new Error('Invalid entity');
    ids.add(entity.id);
  }
  // Kotlin JSON emits explicit nulls; the UI uses undefined for absent parents.
  return { ...value, entities: value.entities.map((entity) => ({ ...entity, parentId: entity.parentId ?? undefined })) } as unknown as TickBatch;
}
