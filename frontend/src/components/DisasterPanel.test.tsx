// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DisasterPanel } from './DisasterPanel';
import type { BrokerHealth } from '../domain/dataSource';

describe('manual disasters', () => {
  let root: Root;
  let host: HTMLDivElement;
  const launch = vi.fn(async () => true);
  const health: BrokerHealth = { status: 'paused', runId: 'colony-1', tick: 5, ticks: 120, stepsPerSecond: 3,
    disasters: { reactorAvailable: true, crocodilesAvailable: 32, monstersAvailable: 2, pending: 0 } };
  beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    launch.mockReset().mockResolvedValue(true);
    host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
  });
  afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.unstubAllGlobals(); });
  const button = (text: string): HTMLButtonElement => Array.from(host.querySelectorAll('button')).find((item) => item.textContent?.includes(text))!;
  const click = async (element: HTMLElement): Promise<void> => { await act(async () => element.click()); };
  const render = async (state: BrokerHealth | undefined = health): Promise<void> => {
    await act(async () => root.render(<DisasterPanel health={state} onLaunch={launch} />));
  };

  it('opens, closes with Escape and the close button, and preserves chosen parameters', async () => {
    await render();
    const toggle = button('Напасти');
    expect(host.querySelector<HTMLElement>('[role="region"]')?.hidden).toBe(true);
    await click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const radius = host.querySelector<HTMLInputElement>('[aria-label="Радиус взрыва, м"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(radius, '120');
      radius.dispatchEvent(new Event('input', { bubbles: true }));
      radius.dispatchEvent(new Event('change', { bubbles: true }));
      radius.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(toggle);
    await click(toggle);
    await click(button('Взорвать реактор'));
    expect(launch).toHaveBeenCalledWith({ kind: 'reactor', radius: 120, damage: 100 }, 'colony-1');
    await click(host.querySelector<HTMLElement>('[aria-label="Закрыть панель напастей"]')!);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it('launches distinct commands and caps the wave to living monsters reported by the server', async () => {
    await render(); await click(button('Напасти'));
    await click(button('Запустить крокодилов'));
    expect(launch).toHaveBeenLastCalledWith({ kind: 'crocodiles', count: 3 }, 'colony-1');
    await click(button('Впустить монстров'));
    expect(launch).toHaveBeenLastCalledWith({ kind: 'monsters', count: 2, duration: 90 }, 'colony-1');
    expect(host.textContent).toContain('сработает на следующем шаге');
    await render({ ...health, disasters: { ...health.disasters!, monstersAvailable: 1, pending: 1 } });
    await click(button('Впустить монстров'));
    expect(launch).toHaveBeenLastCalledWith({ kind: 'monsters', count: 1, duration: 90 }, 'colony-1');
  });

  it('prevents duplicate requests while pending, reports failures, and clears old run feedback', async () => {
    let complete!: (value: boolean) => void;
    launch.mockImplementationOnce(() => new Promise<boolean>((resolve) => { complete = resolve; }));
    await render(); await click(button('Напасти')); await click(button('Запустить крокодилов'));
    expect(button('Впустить монстров').matches(':disabled')).toBe(true);
    await click(button('Запустить крокодилов'));
    expect(launch).toHaveBeenCalledTimes(1);
    await act(async () => complete(false));
    expect(host.textContent).toContain('Не удалось запустить');
    expect(button('Впустить монстров').matches(':disabled')).toBe(false);
    await render({ ...health, runId: 'colony-2' });
    expect(host.textContent).not.toContain('Не удалось запустить');
  });

  it('disables unavailable disasters, disconnected, unsupported and completed simulations', async () => {
    await render(); await click(button('Напасти'));
    for (const state of [undefined, { ...health, disasters: undefined }, { ...health, status: 'completed' as const },
      { ...health, disasters: { reactorAvailable: false, crocodilesAvailable: 0, monstersAvailable: 0, pending: 0 } }]) {
      await act(async () => root.render(<DisasterPanel health={state} onLaunch={launch} />));
      for (const name of ['Взорвать реактор', 'Запустить крокодилов', 'Впустить монстров']) {
        expect(button(name).matches(':disabled')).toBe(true);
        await click(button(name));
      }
    }
    expect(launch).not.toHaveBeenCalled();
  });
});
