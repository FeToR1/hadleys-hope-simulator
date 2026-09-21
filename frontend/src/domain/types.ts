export const KNOWN_ENTITY_TYPES = ['house', 'heater', 'kettle', 'civilian', 'xenomorph', 'power_node'] as const;
/** Kinds the observer may add later are shown generically instead of invalidating the whole snapshot. */
export type EntityType = (typeof KNOWN_ENTITY_TYPES)[number] | 'other';

export type EntityStatus = 'nominal' | 'warning' | 'critical' | 'dead';

export interface EntityMetrics {
  temperature?: number;
  power_consumption?: number;
  water_level?: number;
  stress?: number;
  repair_cost?: number;
  health?: number;
}

export interface EntityState {
  id: string;
  pid: number | null;
  type: EntityType;
  status: EntityStatus;
  metrics: EntityMetrics;
  connectedTo: string[];
  coordinates: { x: number; y: number };
  parentId?: string;
  /** Private state of the entity's VM (behavior variables); absent in mock data. */
  vmState?: Record<string, unknown>;
}

/** A request an entity's program made in one step; the world decides whether it takes effect. */
export interface TraceEffect {
  source: string;
  operation: string;
  arguments: unknown[];
  accepted: boolean;
}

export interface TickBatch {
  version?: 1;
  runId?: string;
  runtimeMode?: 'reference' | 'process' | 'mock';
  seed?: string;
  full?: boolean;
  tickId: number;
  timestamp: number;
  entities: EntityState[];
  effects?: TraceEffect[];
  deliveredEvents?: number;
}

export interface EntityLog {
  id: number;
  timestamp: number;
  entityId: string;
  level: 'info' | 'warning' | 'error';
  message: string;
}

export interface EntityDelta {
  entity: EntityState;
  connectionsChanged: boolean;
}

export interface StateSnapshot {
  runId: string;
  revision: number;
  seed: string;
  runtimeMode: 'reference' | 'process' | 'mock';
  tickId: number;
  timestamp: number;
  entities: ReadonlyMap<string, EntityState>;
  logs: readonly EntityLog[];
}

export type CausalChainStepKind = 'network' | 'thermal' | 'hydraulics' | 'economy';

export interface CausalChainStep {
  id: string;
  kind: CausalChainStepKind;
  title: string;
  detail: string;
  focus: 'graph' | 'map';
  focusEntityId: string;
}
