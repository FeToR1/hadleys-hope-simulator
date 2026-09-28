import { useEffect, useRef, type JSX } from 'react';
import type { AttractorMode, PhasePoint } from '../domain/types';

const CANVAS_SIZE = 250;
const TEMPERATURE_MIN = -10;
const TEMPERATURE_MAX = 25;
const POWER_MIN = 0;
const POWER_MAX = 2_000;

const modeColor: Record<AttractorMode, string> = {
  stationary: '#48d597',
  periodic: '#f2c94c',
  chaotic: '#ff9d59',
  collapse: '#ff4d67',
};

const clamp = (value: number, min: number, max: number): number => Math.min(max, Math.max(min, value));

const toCanvasPoint = (point: PhasePoint): { x: number; y: number } => ({
  x: ((clamp(point.x, TEMPERATURE_MIN, TEMPERATURE_MAX) - TEMPERATURE_MIN) / (TEMPERATURE_MAX - TEMPERATURE_MIN)) * CANVAS_SIZE,
  y: CANVAS_SIZE - ((clamp(point.y, POWER_MIN, POWER_MAX) - POWER_MIN) / (POWER_MAX - POWER_MIN)) * CANVAS_SIZE,
});

export function AttractorRadar({ history, mode }: { history: readonly PhasePoint[]; mode: AttractorMode }): JSX.Element {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext('2d');
    if (canvas === null || context === null || context === undefined) return;
    const ctx: CanvasRenderingContext2D = context;

    let animationFrame = 0;
    const color = modeColor[mode];
    const latest = history.at(-1);
    const collapsePoint = mode === 'collapse' ? history.find((point) => point.x < 0) ?? latest : undefined;

    const draw = (timestamp: number): void => {
      ctx.clearRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);
      ctx.fillStyle = '#020609';
      ctx.fillRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

      ctx.strokeStyle = 'rgba(72, 213, 151, 0.12)';
      ctx.lineWidth = 1;
      for (let offset = 50; offset < CANVAS_SIZE; offset += 50) {
        ctx.beginPath();
        ctx.moveTo(offset, 0);
        ctx.lineTo(offset, CANVAS_SIZE);
        ctx.moveTo(0, offset);
        ctx.lineTo(CANVAS_SIZE, offset);
        ctx.stroke();
      }

      if (history.length > 0) {
        ctx.beginPath();
        history.forEach((point, index) => {
          const canvasPoint = toCanvasPoint(point);
          if (index === 0) ctx.moveTo(canvasPoint.x, canvasPoint.y);
          else ctx.lineTo(canvasPoint.x, canvasPoint.y);
        });
        ctx.strokeStyle = color;
        ctx.globalAlpha = 0.9;
        ctx.lineWidth = 2;
        ctx.stroke();
        ctx.globalAlpha = 1;
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

  return <canvas ref={canvasRef} className="attractor-radar" width={CANVAS_SIZE} height={CANVAS_SIZE} aria-label="Фазовый портрет хаотического аттрактора" />;
}
