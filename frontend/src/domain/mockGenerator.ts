import type { EntityState, TickBatch } from './types';

const HOUSE_COUNT = 300;
const GRID_COLUMNS = 20;

const statusForTemperature = (temperature: number): EntityState['status'] =>
  temperature < 4 ? 'critical' : temperature < 13 ? 'warning' : 'nominal';

const seededRandom = (initial: number): (() => number) => {
  let state = initial >>> 0;
  return () => {
    state = (state * 1_664_525 + 1_013_904_223) >>> 0;
    return state / 4_294_967_296;
  };
};

export class MockDataGenerator {
  private readonly entities = new Map<string, EntityState>();
  private tickId = 0;
  private failureTick = 0;
  private timer: ReturnType<typeof setInterval> | undefined;
  private readonly listeners = new Set<(batch: TickBatch) => void>();
  public readonly simulationSeed = 'lv426-demo-2026';

  private readonly random: () => number;
  public constructor(random?: () => number) {
    this.random = random ?? seededRandom(0x4262026);
    this.seed();
  }

  private seed(): void {
    for (let index = 0; index < HOUSE_COUNT; index += 1) {
      const x = (index % GRID_COLUMNS) * 70;
      const y = Math.floor(index / GRID_COLUMNS) * 70;
      const houseId = `house-${index + 1}`;
      this.entities.set(houseId, {
        id: houseId, pid: 10_000 + index, type: 'house', status: 'nominal',
        metrics: { temperature: 20, water_level: 100 }, connectedTo: ['power-1'],
        coordinates: { x, y },
      });
      for (const [deviceIndex, type] of (['heater', 'kettle'] as const).entries()) {
        const deviceId = `${type}-${index + 1}`;
        this.entities.set(deviceId, {
          id: deviceId, pid: 20_000 + index * 2 + deviceIndex, type, status: 'nominal',
          metrics: { power_consumption: type === 'heater' ? 1_200 : 80 }, connectedTo: [houseId],
          coordinates: { x: x + 10 + deviceIndex * 12, y: y + 10 }, parentId: houseId,
        });
      }
      if (index < HOUSE_COUNT) {
        const civilianId = `civilian-${index + 1}`;
        this.entities.set(civilianId, {
          id: civilianId, pid: 30_000 + index, type: 'civilian', status: 'nominal',
          metrics: { stress: 0.1 }, connectedTo: [houseId],
          coordinates: { x: x + 25, y: y + 25 }, parentId: houseId,
        });
      }
    }
    for (let index = 0; index < 8; index += 1) {
      this.entities.set(`xenomorph-${index + 1}`, {
        id: `xenomorph-${index + 1}`, pid: 40_000 + index, type: 'xenomorph', status: 'nominal',
        metrics: { stress: 0.8 }, connectedTo: [], coordinates: { x: 250 + index * 15, y: 300 + index * 8 },
      });
    }
    this.entities.set('power-1', {
      id: 'power-1', pid: 90_001, type: 'power_node', status: 'nominal',
      metrics: { power_consumption: 0 }, connectedTo: Array.from({ length: HOUSE_COUNT }, (_, index) => `house-${index + 1}`),
      coordinates: { x: 700, y: 700 },
    });
    this.entities.set('power-2', {
      id: 'power-2', pid: 90_002, type: 'power_node', status: 'nominal',
      metrics: { power_consumption: 0 }, connectedTo: ['power-1'], coordinates: { x: 770, y: 700 },
    });
  }

  public subscribe(listener: (batch: TickBatch) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  public getInitialBatch(): TickBatch {
    return { tickId: this.tickId, timestamp: Date.now(), entities: [...this.entities.values()] };
  }

  public step(): TickBatch {
    this.tickId += 1;
    const shouldFail = this.tickId > 0 && this.tickId - this.failureTick > 35 &&
      (this.tickId % 60 === 0 || this.random() > 0.97);
    if (shouldFail) this.failureTick = this.tickId;
    for (const entity of this.entities.values()) {
      if (entity.type === 'civilian' || entity.type === 'xenomorph') {
        entity.coordinates.x += (this.random() - 0.5) * 5;
        entity.coordinates.y += (this.random() - 0.5) * 5;
      }
      if (entity.type === 'house') {
        const powerFailed = this.failureTick > 0 && this.tickId - this.failureTick < 18;
        const temperature = (entity.metrics.temperature ?? 20) + (powerFailed ? -0.8 : (this.random() - 0.5) * 0.5);
        entity.metrics.temperature = Math.max(-10, Math.min(24, temperature));
        entity.status = statusForTemperature(temperature);
        entity.metrics.water_level = Math.max(0, (entity.metrics.water_level ?? 100) - (powerFailed ? 2 : 0.05));
        if (powerFailed && temperature < 4) entity.metrics.repair_cost = 500;
      }
    }
    if (shouldFail) this.entities.get('power-1')!.status = 'dead';
    if (this.failureTick > 0 && this.tickId - this.failureTick >= 18) this.entities.get('power-1')!.status = 'nominal';
    const batch = { tickId: this.tickId, timestamp: Date.now(), entities: [...this.entities.values()] };
    for (const listener of this.listeners) listener(batch);
    return batch;
  }

  public start(intervalMs = 300): () => void {
    this.stop();
    this.timer = setInterval(() => this.step(), Math.max(200, Math.min(500, intervalMs)));
    return () => this.stop();
  }

  public stop(): void {
    if (this.timer !== undefined) clearInterval(this.timer);
    this.timer = undefined;
  }
}
