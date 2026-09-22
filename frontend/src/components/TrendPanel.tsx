import { useCallback, useEffect, useMemo, useRef, useState, type JSX, type KeyboardEvent, type PointerEvent } from 'react';
import type { TrendSample } from '../domain/trend';

/**
 * Four measures of the settlement over time, as small multiples: one chart per measure, each with its own
 * scale, because a shared axis would put watts and degrees on one ruler. A shared crosshair reads the same
 * step in all four, and the table view keeps every value reachable without hovering.
 */
interface TrendPanelProps {
  trend: readonly TrendSample[];
  /** Seconds of model time per step, for the time axis. */
  stepSeconds?: number;
}

interface Measure {
  key: 'temperature' | 'power' | 'dryHouses' | 'spend';
  title: string;
  color: string;
  format: (value: number) => string;
  /** Include zero in the scale when the reader needs the distance to it. */
  zeroBased: boolean;
}

/** Validated against the dashboard surface for both colour vision and contrast; see the dataviz palette. */
const MEASURES: readonly Measure[] = [
  { key: 'temperature', title: 'Средняя температура домов', color: '#3987e5', format: (v) => `${v.toFixed(1)} °C`, zeroBased: false },
  { key: 'power', title: 'Выданная мощность', color: '#199e70', format: formatPower, zeroBased: true },
  { key: 'dryHouses', title: 'Дома без воды', color: '#c98500', format: (v) => `${Math.round(v)}`, zeroBased: true },
  { key: 'spend', title: 'Начислено всего', color: '#d55181', format: (v) => Math.round(v).toLocaleString('ru-RU'), zeroBased: true },
];

function formatPower(watts: number): string {
  if (Math.abs(watts) >= 1000) return `${(watts / 1000).toFixed(1)} кВт`;
  return `${Math.round(watts)} Вт`;
}

/** Model time as hours, minutes and seconds; a step is a model second by default. */
function formatTime(tick: number, stepSeconds: number): string {
  const total = Math.round(tick * stepSeconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

const GEOMETRY = { height: 128, padLeft: 72, padRight: 104, padTop: 24, padBottom: 24 };

export function TrendPanel({ trend, stepSeconds = 1 }: TrendPanelProps): JSX.Element {
  const host = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(720);
  const [hover, setHover] = useState<number>();
  const [asTable, setAsTable] = useState(false);

  useEffect(() => {
    const element = host.current;
    if (element === null || typeof ResizeObserver === 'undefined') return;
    // Real pixels rather than a scaled viewBox, so a 2px line stays 2px at any width.
    const measure = () => setWidth(Math.max(320, element.clientWidth));
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    measure();
    return () => observer.disconnect();
  }, []);

  const index = hover !== undefined && hover < trend.length ? hover : trend.length - 1;
  const plotWidth = Math.max(1, width - GEOMETRY.padLeft - GEOMETRY.padRight);
  const xOf = useCallback(
    (position: number) => GEOMETRY.padLeft + (trend.length < 2 ? plotWidth / 2 : (position / (trend.length - 1)) * plotWidth),
    [plotWidth, trend.length],
  );

  const onPointer = (event: PointerEvent<HTMLDivElement>) => {
    if (trend.length === 0) return;
    const box = event.currentTarget.getBoundingClientRect();
    const ratio = (event.clientX - box.left - GEOMETRY.padLeft) / plotWidth;
    setHover(Math.min(trend.length - 1, Math.max(0, Math.round(ratio * (trend.length - 1)))));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (trend.length === 0) return;
    const step = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0;
    if (step === 0) return;
    event.preventDefault();
    setHover(Math.min(trend.length - 1, Math.max(0, index + step)));
  };

  if (trend.length === 0) {
    return <div className="trend-empty"><p className="muted">Графики появятся, как только пойдут шаги симуляции.</p></div>;
  }

  const current = trend[index];
  return (
    <div className="trend" ref={host}>
      <div className="trend-head">
        <span className="trend-scope">
          Посёлок · шаг {current.tick} · {formatTime(current.tick, stepSeconds)} модельного времени · окно {trend.length}
        </span>
        <button type="button" className="trend-toggle" aria-pressed={asTable} onClick={() => setAsTable(!asTable)}>
          {asTable ? 'Графики' : 'Таблица'}
        </button>
      </div>
      {asTable ? (
        <TrendTable trend={trend} stepSeconds={stepSeconds} />
      ) : (
        <div
          className="trend-charts"
          role="group"
          aria-label="Графики метрик посёлка"
          tabIndex={0}
          onPointerMove={onPointer}
          onPointerLeave={() => setHover(undefined)}
          onKeyDown={onKeyDown}
        >
          {MEASURES.map((measure) => (
            <Chart key={measure.key} measure={measure} trend={trend} width={width} xOf={xOf} index={index} stepSeconds={stepSeconds} />
          ))}
        </div>
      )}
    </div>
  );
}

interface ChartProps {
  measure: Measure;
  trend: readonly TrendSample[];
  width: number;
  xOf: (position: number) => number;
  index: number;
  stepSeconds: number;
}

function Chart({ measure, trend, width, xOf, index, stepSeconds }: ChartProps): JSX.Element {
  const { height, padLeft, padRight, padTop, padBottom } = GEOMETRY;
  const values = trend.map((sample) => sample[measure.key]);
  const scale = useMemo(() => {
    let low = Math.min(...values);
    let high = Math.max(...values);
    if (measure.zeroBased) low = Math.min(0, low);
    if (high - low < 1e-9) high = low + 1;
    const room = (high - low) * 0.12;
    // A count or a sum has no meaning below zero, so its axis stops there instead of padding into the negative.
    return { low: measure.zeroBased && low >= 0 ? 0 : low - room, high: high + room };
  }, [measure.zeroBased, values.join(',')]);

  const plotHeight = height - padTop - padBottom;
  const yOf = (value: number) => padTop + plotHeight * (1 - (value - scale.low) / (scale.high - scale.low));
  const points = values.map((value, position) => `${xOf(position).toFixed(1)},${yOf(value).toFixed(1)}`);
  const line = `M${points.join('L')}`;
  const area = `${line}L${xOf(values.length - 1).toFixed(1)},${yOf(scale.low).toFixed(1)}L${xOf(0).toFixed(1)},${yOf(scale.low).toFixed(1)}Z`;
  const last = values[values.length - 1];
  const hovered = values[index];

  return (
    <figure className="trend-chart">
      <svg width={width} height={height} role="img" aria-label={`${measure.title}: ${measure.format(last)}`}>
        <text className="trend-title" x={padLeft} y={14}>{measure.title}</text>
        {[scale.high, (scale.high + scale.low) / 2, scale.low].map((value) => (
          <g key={value}>
            <line className="trend-grid" x1={padLeft} x2={width - padRight} y1={yOf(value)} y2={yOf(value)} />
            <text className="trend-tick" x={padLeft - 8} y={yOf(value) + 4} textAnchor="end">{measure.format(value)}</text>
          </g>
        ))}
        <path d={area} fill={measure.color} fillOpacity={0.1} />
        <path d={line} fill="none" stroke={measure.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        <line className="trend-crosshair" x1={xOf(index)} x2={xOf(index)} y1={padTop} y2={height - padBottom} />
        {/* The end marker carries a surface ring so it stays legible where it crosses the line. */}
        <circle cx={xOf(values.length - 1)} cy={yOf(last)} r={4} fill={measure.color} stroke="#0d1a2b" strokeWidth={2} />
        {index !== values.length - 1 && (
          <circle cx={xOf(index)} cy={yOf(hovered)} r={4} fill={measure.color} stroke="#0d1a2b" strokeWidth={2} />
        )}
        <text className="trend-value" x={width - padRight + 12} y={yOf(index === values.length - 1 ? last : hovered) + 4}>
          {measure.format(index === values.length - 1 ? last : hovered)}
        </text>
        <text className="trend-tick" x={padLeft} y={height - 8}>{formatTime(trend[0].tick, stepSeconds)}</text>
        <text className="trend-tick" x={width - padRight} y={height - 8} textAnchor="end">
          {formatTime(trend[trend.length - 1].tick, stepSeconds)}
        </text>
      </svg>
    </figure>
  );
}

function TrendTable({ trend, stepSeconds }: { trend: readonly TrendSample[]; stepSeconds: number }): JSX.Element {
  const rows = trend.slice(-40).reverse();
  return (
    <div className="trend-table-wrap">
      <table className="trend-table">
        <thead>
          <tr>
            <th scope="col">Шаг</th>
            <th scope="col">Время</th>
            {MEASURES.map((measure) => <th scope="col" key={measure.key}>{measure.title}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map((sample) => (
            <tr key={sample.tick}>
              <td>{sample.tick}</td>
              <td>{formatTime(sample.tick, stepSeconds)}</td>
              {MEASURES.map((measure) => <td key={measure.key}>{measure.format(sample[measure.key])}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
