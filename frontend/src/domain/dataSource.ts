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
    const check = async (): Promise<void> => {
      try {
        const response = await fetch(this.options.healthUrl, { method: 'GET', cache: 'no-store' });
        this.options.onHealthChange(response.ok);
      } catch {
        this.options.onHealthChange(false);
      }
    };
    void check();
    this.healthTimer = setInterval(() => void check(), 2_000);
    return () => {
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
        this.options.onBatch(JSON.parse(event.data) as TickBatch);
      } catch {
        this.options.onError('Live Broker прислал некорректный TickBatch');
      }
    };
    this.stream.onerror = () => this.options.onError('Соединение с Live Broker потеряно');
  }

  public disconnect(): void {
    this.stream?.close();
    this.stream = undefined;
  }
}
