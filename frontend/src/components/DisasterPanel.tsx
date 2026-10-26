import { useEffect, useRef, useState, type JSX } from 'react';
import type { BrokerHealth, DisasterAction } from '../domain/dataSource';

interface DisasterPanelProps {
  health: BrokerHealth | undefined;
  onLaunch: (action: DisasterAction, runId: string) => Promise<boolean>;
}

export function DisasterPanel({ health, onLaunch }: DisasterPanelProps): JSX.Element {
  const [open, setOpen] = useState(false);
  const [radius, setRadius] = useState(60);
  const [damage, setDamage] = useState(100);
  const [crocodiles, setCrocodiles] = useState(3);
  const [monsters, setMonsters] = useState(3);
  const [duration, setDuration] = useState(90);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{ runId: string; error: boolean; text: string }>();
  const toggle = useRef<HTMLButtonElement>(null);
  const inFlight = useRef(false);
  const options = health?.disasters;
  const enabled = health !== undefined && ['waiting', 'running', 'paused'].includes(health.status) && options !== undefined;
  const crocMax = options?.crocodilesAvailable ?? 0;
  const monsterMax = options?.monstersAvailable ?? 0;
  const crocCount = Math.min(crocodiles, crocMax);
  const monsterCount = Math.min(monsters, monsterMax);

  const close = (): void => { setOpen(false); toggle.current?.focus(); };
  useEffect(() => {
    if (!open) return;
    const onEscape = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') { setOpen(false); toggle.current?.focus(); }
    };
    window.addEventListener('keydown', onEscape);
    return () => window.removeEventListener('keydown', onEscape);
  }, [open]);
  const launch = async (action: DisasterAction): Promise<void> => {
    if (!enabled || health === undefined || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setFeedback(undefined);
    try {
      const accepted = await onLaunch(action, health.runId);
      setFeedback({ runId: health.runId, error: !accepted, text: accepted
        ? 'Напасть в очереди — сработает на следующем шаге.'
        : 'Не удалось запустить напасть. Подробности — в статусе соединения.' });
    } catch {
      setFeedback({ runId: health.runId, error: true, text: 'Не удалось отправить команду. Попробуйте ещё раз.' });
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  return <div className="disaster-control">
    <button ref={toggle} type="button" className="disaster-toggle" aria-expanded={open} aria-controls="disaster-panel"
      onClick={() => setOpen(!open)}><span aria-hidden="true">⚠</span> Напасти {options?.pending ? `· ${options.pending}` : ''}</button>
    <div id="disaster-panel" className="disaster-panel" role="region" aria-label="Управление напастями" hidden={!open}>
      <div className="disaster-heading"><div><span className="eyebrow">РУЧНОЕ ВМЕШАТЕЛЬСТВО</span><h2>Напасти</h2></div>
        <button type="button" className="disaster-close" aria-label="Закрыть панель напастей" onClick={close}>×</button></div>
      <p className="disaster-help">События меняют мир на следующем шаге. На паузе нажмите «Один шаг» или продолжите симуляцию.</p>
      {!enabled && <p className="disaster-unavailable">{health === undefined ? 'Ожидаем соединения с симуляцией.'
        : options === undefined ? 'Этот сервер или сценарий не поддерживает напасти.' : 'Прогон завершён. Начните заново для запуска напастей.'}</p>}
      <fieldset className="disaster-card" disabled={!enabled || busy || !options?.reactorAvailable}>
        <legend>Взрыв реактора</legend>
        <p>Реактор разрушается. Объекты в радиусе взрыва получают заданный урон.</p>
        <label>Радиус взрыва <output>{radius} м</output><input aria-label="Радиус взрыва, м" type="range" min={0} max={300} step={10} value={radius} onChange={(event) => setRadius(Number(event.target.value))} /></label>
        <label>Урон вокруг <output>{damage} HP</output><input aria-label="Урон вокруг реактора, HP" type="range" min={0} max={200} step={10} value={damage} onChange={(event) => setDamage(Number(event.target.value))} /></label>
        <button type="button" onClick={() => void launch({ kind: 'reactor', radius, damage })}>Взорвать реактор</button>
        {enabled && !options?.reactorAvailable && <small>Реактор разрушен или взрыв уже в очереди.</small>}
      </fieldset>
      <fieldset className="disaster-card" disabled={!enabled || busy || crocMax === 0}>
        <legend>Летающие крокодилы</legend>
        <p>Стая вылетит из леса, пройдёт над посёлком и вернётся с моря.</p>
        <label>Количество <output>{crocCount}</output><input aria-label="Количество крокодилов" type="range" min={1} max={Math.max(1, crocMax)} value={Math.max(1, crocCount)} onChange={(event) => setCrocodiles(Number(event.target.value))} /></label>
        <button type="button" onClick={() => void launch({ kind: 'crocodiles', count: crocCount })}>Запустить крокодилов</button>
        {enabled && crocMax === 0 && <small>Все места заняты. Дождитесь возвращения стаи.</small>}
      </fieldset>
      <fieldset className="disaster-card" disabled={!enabled || busy || monsterMax === 0}>
        <legend>Вторжение монстров</legend>
        <p>Живые монстры из сценария попадут внутрь посёлка и начнут охоту.</p>
        <label>Количество <output>{monsterCount}</output><input aria-label="Количество монстров" type="range" min={1} max={Math.max(1, monsterMax)} value={Math.max(1, monsterCount)} onChange={(event) => setMonsters(Number(event.target.value))} /></label>
        <label>Окно атаки <output>{duration} с</output><input aria-label="Длительность атаки монстров, с" type="range" min={10} max={600} step={10} value={duration} onChange={(event) => setDuration(Number(event.target.value))} /></label>
        <button type="button" onClick={() => void launch({ kind: 'monsters', count: monsterCount, duration })}>Впустить монстров</button>
        {enabled && monsterMax === 0 && <small>Нет доступных живых монстров; предыдущая волна ещё может атаковать.</small>}
      </fieldset>
      <div className="disaster-feedback" role="status" aria-live="polite">
        {feedback?.runId === health?.runId && <p className={feedback?.error ? 'disaster-unavailable' : ''}>{feedback?.text}</p>}
        {(options?.pending ?? 0) > 0 && <p>В очереди: {options?.pending}</p>}
      </div>
    </div>
  </div>;
}
