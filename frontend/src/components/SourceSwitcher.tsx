import type { JSX } from 'react';
import type { DataSourceMode } from '../domain/dataSource';

interface SourceSwitcherProps {
  mode: DataSourceMode;
  brokerAvailable: boolean;
  warning?: string;
  onChange: (mode: DataSourceMode) => void;
}

export function SourceSwitcher({ mode, brokerAvailable, warning, onChange }: SourceSwitcherProps): JSX.Element {
  return <div className="source-switcher">
    <div className="source-options" role="radiogroup" aria-label="Источник данных">
      <button type="button" className={mode === 'mock' ? 'active' : ''} onClick={() => onChange('mock')}>Имитация</button>
      <button type="button" className={mode === 'live' ? 'active' : ''} onClick={() => onChange('live')}>Брокер</button>
    </div>
    <span className={`broker-health ${brokerAvailable ? 'online' : 'offline'}`}><i /> {brokerAvailable ? 'Connected' : 'Offline'}</span>
    {warning !== undefined && <span className="source-warning" role="status">{warning}</span>}
  </div>;
}
