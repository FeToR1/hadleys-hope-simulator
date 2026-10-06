import { useEffect, useRef, type JSX } from 'react';
import type { AttractorMode, PhasePoint } from '../domain/types';
import { phaseBounds, projectPhasePoint } from '../domain/attractor';

const CANVAS_SIZE = 250;
const PLOT = { left: 40, top: 15, width: 198, height: 203 };

const modeColor: Record<AttractorMode, string> = {
  stationary: '#48d597',
  periodic: '#f2c94c',
  chaotic: '#ff9d59',
  collapse: '#ff4d67',
  transient: '#73baff',
};

export function AttractorRadar({ history, mode }: { history: readonly PhasePoint[]; mode: AttractorMode }): JSX.Element {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const latest = history.at(-1);
  const explosion = history.filter((point) => point.reactorExplosion).at(-1);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext('2d');
    if (canvas === null || context === null || context === undefined) return;
    const ctx: CanvasRenderingContext2D = context;

    let animationFrame = 0;
    const color = modeColor[mode];
    const latest = history.at(-1);
    const collapsePoint = mode === 'collapse' ? history.find((point) => point.x < 0) ?? latest : undefined;
    const bounds = phaseBounds(history);
    const toCanvasPoint = (point: PhasePoint): { x: number; y: number } => {
      const scaled = projectPhasePoint(point, bounds);
      return { x: PLOT.left + scaled.x * PLOT.width, y: PLOT.top + (1 - scaled.y) * PLOT.height };
    };
    const trail = history.map(toCanvasPoint);
    const explosions = history.filter((point) => point.reactorExplosion).map(toCanvasPoint);

    const draw = (timestamp: number): void => {
      ctx.clearRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);
      ctx.fillStyle = '#020609';
      ctx.fillRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

      ctx.strokeStyle = 'rgba(72, 213, 151, 0.12)';
      ctx.lineWidth = 1;
      for (let index = 0; index <= 4; index++) {
        const x = PLOT.left + PLOT.width * index / 4;
        const y = PLOT.top + PLOT.height * index / 4;
        ctx.beginPath();
        ctx.moveTo(x, PLOT.top);
        ctx.lineTo(x, PLOT.top + PLOT.height);
        ctx.moveTo(PLOT.left, y);
        ctx.lineTo(PLOT.left + PLOT.width, y);
        ctx.stroke();
      }
      ctx.fillStyle = '#91a8c2';
      ctx.font = '9px system-ui';
      ctx.textAlign = 'right';
      for (let index = 0; index <= 2; index++) {
        ctx.fillText((bounds.y[1] * (1 - index / 2) / 1000).toFixed(2), PLOT.left - 5, PLOT.top + PLOT.height * index / 2 + 3);
      }
      ctx.textAlign = 'left';
      ctx.fillText('МВт', 4, 10);
      ctx.fillText(bounds.x[0].toFixed(1), PLOT.left, 233);
      ctx.textAlign = 'right';
      ctx.fillText(`${bounds.x[1].toFixed(1)} °C`, PLOT.left + PLOT.width, 233);

      if (history.length > 0) {
        ctx.beginPath();
        trail.forEach((canvasPoint, index) => {
          if (index === 0) ctx.moveTo(canvasPoint.x, canvasPoint.y);
          else ctx.lineTo(canvasPoint.x, canvasPoint.y);
        });
        ctx.strokeStyle = color;
        ctx.globalAlpha = 0.9;
        ctx.lineWidth = 2;
        ctx.stroke();
        ctx.globalAlpha = 1;
      }

      for (const point of explosions) {
        ctx.strokeStyle = '#ff9d59';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(point.x, point.y - 6);
        ctx.lineTo(point.x + 6, point.y);
        ctx.lineTo(point.x, point.y + 6);
        ctx.lineTo(point.x - 6, point.y);
        ctx.closePath();
        ctx.stroke();
      }

      if (collapsePoint !== undefined) {
        const point = toCanvasPoint(collapsePoint);
        ctx.strokeStyle = '#ff304f';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(point.x - 7, point.y - 7);
        ctx.lineTo(point.x + 7, point.y + 7);
        ctx.moveTo(point.x + 7, point.y - 7);
        ctx.lineTo(point.x - 7, point.y + 7);
        ctx.stroke();
      }

      if (latest !== undefined) {
        const point = toCanvasPoint(latest);
        const pulse = 0.45 + (Math.sin(timestamp / 180) + 1) * 0.275;
        ctx.fillStyle = color;
        ctx.globalAlpha = pulse;
        ctx.beginPath();
        ctx.arc(point.x, point.y, 4, 0, Math.PI * 2);
        ctx.fill();
        ctx.globalAlpha = 1;
      }

      animationFrame = requestAnimationFrame(draw);
    };

    animationFrame = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animationFrame);
  }, [history, mode]);

  return <div className="attractor-portrait">
    <canvas ref={canvasRef} className="attractor-radar" width={CANVAS_SIZE} height={CANVAS_SIZE} aria-label="Фазовый портрет: температура и потребление энергии" />
    <p className="attractor-axis-help">X — средняя температура · Y — потребление. Масштаб по истории.</p>
    {latest !== undefined && <div className="attractor-readout">
      <span>{latest.x.toFixed(2)} °C</span><span>{(latest.y / 1000).toFixed(3)} МВт</span>
      <span>Баланс: {latest.z.toLocaleString('ru-RU')} ед.</span>
    </div>}
    {explosion !== undefined && <p className="attractor-event">◇ Взрыв реактора · тик {explosion.tickId}</p>}
  </div>;
}
