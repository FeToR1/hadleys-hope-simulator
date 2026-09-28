import type { JSX } from 'react';

export function BrokerConnection({ connected, warning }: { connected: boolean; warning?: string }): JSX.Element {
  return <div className="broker-connection">
    <span className={`broker-health ${connected ? 'online' : 'offline'}`} role="status"><i />{connected ? 'Симуляция подключена' : 'Подключение к симуляции…'}</span>
    {warning && <span className="source-warning" role="status">{warning}</span>}
  </div>;
}
