// @vitest-environment jsdom
import { act, useEffect, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TickBatch } from './domain/types';
import { App } from './App';

const harness = vi.hoisted(() => ({
  connections: [] as Array<{ onBatch: (batch: TickBatch) => void; onConnectionChange: (connected: boolean) => void }>,
  mounts: 0,
}));

vi.mock('./domain/dataSource', () => ({
  LiveBrokerSource: class {
    constructor(private readonly options: (typeof harness.connections)[number]) {}
    connect(): void { harness.connections.push(this.options); this.options.onConnectionChange(true); }
    disconnect(): void {}
    control(): Promise<undefined> { return Promise.resolve(undefined); }
  },
}));
vi.mock('./components/SettlementMap', () => ({
  SettlementMap: () => {
    const [zoom, setZoom] = useState(1);
    useEffect(() => { harness.mounts++; }, []);
    return <button data-testid="map-camera" onClick={() => setZoom(zoom + 1)}>{zoom}</button>;
  },
}));
vi.mock('./components/Sidebar', () => ({ Sidebar: () => null }));
vi.mock('./components/NetworkTopology', () => ({ NetworkTopology: () => null }));
vi.mock('./components/TrendPanel', () => ({ TrendPanel: () => null }));

describe('live simulation and map lifecycle', () => {
  let root: Root;
  let host: HTMLDivElement;
  beforeEach(async () => {
    vi.useFakeTimers();
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    harness.connections.length = 0; harness.mounts = 0;
    host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
    await act(async () => { root.render(<App />); });
  });
  afterEach(async () => {
    await act(async () => { root.unmount(); });
    host.remove(); vi.useRealTimers(); vi.unstubAllGlobals();
  });
  const frame = (tickId: number, connectedTo: string[] = []): TickBatch => ({
    version: 1, runId: 'colony-1', seed: '426', runtimeMode: 'process', tickId, timestamp: tickId * 1000, full: true,
    entities: [{ id: 'person', type: 'civilian', pid: 123, status: 'nominal', metrics: {}, connectedTo, coordinates: { x: tickId, y: 0 } }],
  });
  async function deliver(batch: TickBatch): Promise<void> {
    await act(async () => { harness.connections[0].onBatch(batch); await vi.advanceTimersByTimeAsync(40); });
  }

  it('connects automatically without a demo selector or generated population', () => {
    expect(harness.connections).toHaveLength(1);
    expect(host.textContent).not.toContain('Имитация');
    expect(host.textContent).toContain('0 объектов');
    expect(host.querySelector('[role="radiogroup"]')).toBeNull();
  });

  it('preserves the map and camera across boarding, alighting and entity removal', async () => {
    await deliver(frame(0));
    const mounted = harness.mounts;
    const camera = host.querySelector('[data-testid="map-camera"]')!;
    await act(async () => { camera.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
    await deliver(frame(1, ['rover']));
    await deliver(frame(2));
    await deliver({ ...frame(3), entities: [] });
    expect(harness.mounts).toBe(mounted);
    expect(host.querySelector('[data-testid="map-camera"]')).toBe(camera);
    expect(camera.textContent).toBe('2');
    await deliver({ ...frame(0), runId: 'colony-2' });
    expect(harness.mounts).toBeGreaterThan(mounted);
    expect(host.querySelector('[data-testid="map-camera"]')?.textContent).toBe('1');
  });
});
