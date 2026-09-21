export type EntityType =
  | 'house'
  | 'heater'
  | 'kettle'
  | 'civilian'
  | 'xenomorph'
  | 'power_node';

export type EntityStatus = 'nominal' | 'warning' | 'critical' | 'dead';

export interface EntityMetrics {
  temperature?: number;
  power_consumption?: number;
  water_level?: number;
  stress?: number;
  repair_cost?: number;
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
