import type { JSX } from 'react';
import type { BrokerHealth, ControlAction, RunStatus } from '../domain/dataSource';

const SPEEDS: readonly number[] = [0.5, 1, 2, 3, 5, 10];

const STATUS_LABEL: Record<RunStatus, string> = {
  waiting: 'ожидание',
  running: 'идёт',
  paused: 'пауза',
  completed: 'завершён',
  failed: 'ошибка',
};

export type RunCommand = ControlAction | 'restart';

interface RunControlsProps {
  health: BrokerHealth | undefined;
  /** Last step of the stream; the health document is only polled and lags behind it. */
  currentTick?: number;
  onControl: (command: RunCommand) => void;
}

/** Pause, single step, restart and pace of the observed run. Steps never change; only when they happen. */
export function RunControls({ health, currentTick, onControl }: RunControlsProps): JSX.Element {
  const status = health?.status;
  const running = status === 'running';
  const speeds = health !== undefined && !SPEEDS.includes(health.stepsPerSecond)
    ? [...SPEEDS, health.stepsPerSecond].sort((a, b) => a - b)
    : SPEEDS;
  return <div className="run-controls" aria-label="Управление запуском">
    <button type="button" title={running ? 'Пауза' : 'Продолжить'} disabled={status !== 'running' && status !== 'paused' && status !== 'waiting'}
      onClick={() => onControl(running ? 'pause' : 'resume')}>{running ? '⏸' : '▶'}</button>
    <button type="button" title="Один шаг" disabled={status !== 'paused' && status !== 'waiting'} onClick={() => onControl('step')}>⏭</button>
    <button type="button" title="Начать заново" disabled={health === undefined} onClick={() => onControl('restart')}>⟲</button>
    <select aria-label="Скорость, шагов в секунду" value={health?.stepsPerSecond ?? 3} disabled={health === undefined}
      onChange={(event) => onControl({ speed: Number(event.target.value) })}>
      {speeds.map((speed) => <option key={speed} value={speed}>{speed} шаг/с</option>)}
    </select>
    <span className={`run-status run-${status ?? 'unknown'}`} title={health?.error}>{describeRun(health, currentTick)}</span>
  </div>;
}

export function describeRun(health: BrokerHealth | undefined, currentTick?: number): string {
  if (health === undefined) return 'нет связи';
  if (health.status === 'failed') return `ошибка: ${health.error ?? 'неизвестна'}`;
  // The stream is the source of truth for progress; the polled document may be a couple of seconds old.
  const done = Math.max(0, (currentTick ?? health.tick) + 1);
  return `${STATUS_LABEL[health.status]} · ${Math.min(done, health.ticks)} / ${health.ticks}`;
}
