export const KNOWN_ENTITY_TYPES = ['house', 'mine', 'heater', 'kettle', 'civilian', 'xenomorph', 'rover', 'marine', 'power_node', 'fence'] as const;
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
  /** Money the world has charged this owner so far, in minimal units. */
  spend?: number;
  occupants?: number;
  passenger_count?: number;
  passenger_capacity?: number;
  squad_size?: number;
  workers?: number;
}

/** One line of the world's ledger: who was charged, what for, and how much. */
export interface Posting {
  tick: number;
  owner: string;
  kind: string;
  amount: number;
  detail?: string;
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
  /** Behavior variables, or descriptive state for a world-owned site. */
  vmState?: Record<string, unknown>;
}

/** A request an entity's program made in one step; the world decides whether it takes effect. */
export interface TraceEffect {
  source: string;
  operation: string;
  arguments: unknown[];
  accepted: boolean;
}

/** A fact the world established; causationId links it to the request or event that caused it. */
export interface WorldEvent {
  id: string;
  type: string;
  tick: number;
  entityId: string;
  actorId?: string;
  causationId?: string;
  fields: Record<string, unknown>;
  recipients: string[];
}

export interface TickBatch {
  version?: 1;
  runId?: string;
  runtimeMode?: 'reference' | 'process';
  seed?: string;
  scenario?: 'storm' | 'weekend' | 'xenomorph' | 'marines';
  full?: boolean;
  tickId: number;
  timestamp: number;
  entities: EntityState[];
  effects?: TraceEffect[];
  events?: WorldEvent[];
  postings?: Posting[];
  deliveredEvents?: number;
}

export interface PhasePoint {
  tickId: number;
  x: number;
  y: number;
  z: number;
}

export type AttractorMode = 'stationary' | 'periodic' | 'chaotic' | 'collapse';

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
  runtimeMode: 'reference' | 'process' | null;
  tickId: number;
  timestamp: number;
  entities: ReadonlyMap<string, EntityState>;
  logs: readonly EntityLog[];
  /** Recent world events of an observed run, oldest first. */
  events: readonly WorldEvent[];
  /** Recent ledger lines, oldest first, and the total charged per owner. */
  postings: readonly Posting[];
  spendByOwner: ReadonlyMap<string, number>;
  /** One row per recent step, oldest first: what the charts plot. */
  trend: readonly import('./trend').TrendSample[];
  attractorHistory: readonly PhasePoint[];
  attractorMode: AttractorMode;
}

export type CausalChainStepKind = 'network' | 'thermal' | 'hydraulics' | 'economy' | 'action' | 'failure';

export interface CausalChainStep {
  id: string;
  kind: CausalChainStepKind;
  title: string;
  detail: string;
  focus: 'graph' | 'map';
  focusEntityId: string;
}
