# LV-426 Settlement Simulation: FRONTEND INTEGRATION CONTRACT & TECHNICAL REQUIREMENTS
Target Architecture: Node.js v24.14.0 | React | PixiJS v8 | AntV G6
Authoritative Specification for Backend, Broker, and Simulation Developers

---

## 1. DATA TRANSPORT & PROTOCOLS
1.1 BASE STREAMING
Data must be streamed from the Broker/World Kernel via SSE (Server-Sent Events) or WebSockets.
Endpoints MUST support automated status checking (e.g., HTTP 200 on GET /health or /stream before establishing EventSource).

1.2 BATCHING CADENCE
Individual VM messages must NOT be sent standalone. The World Kernel aggregates all 1300+ VM execution intentions, resolves physics, and emits a single, flattened `TickBatch` exactly once every 100-500ms.

---

## 2. STRICT TYPE DEFINITIONS (JSON SPECIFICATION)

```typescript
export interface EntityState {
  id: string;        // Unique identifier preserved across ticks (e.g., 'house-42')
  pid: number;       // Actual OS Process ID of the stack VM
  type: 'house' | 'heater' | 'kettle' | 'civilian' | 'xenomorph' | 'power_node';
  status: 'nominal' | 'warning' | 'critical' | 'dead';
  metrics: {
    temperature: number;       // Crucial for thermal calculations & coloring
    power_consumption: number; // Electrical grid utilization benchmarks
    water_level: number;       // Hydraulic simulation state
    sewage_status: number;     // Aeration/infrastructure integrity metric (0-100)
    stress?: number;           // Entity behavior modifier (citizens/xenomorphs)
  };
  connectedTo: string[]; // DIRECTED edges ONLY (Source -> Target). No cyclic loops!
  coordinates: { x: number; y: number };
}

export interface CausalEvent {
  id: string;               // Unique tracking identifier
  chainGroupId: string;     // Grouping key for the cascade scenario (e.g., 'blackout-chain-1')
  timestamp: number;        // Epoch millisecond execution timestamp
  subsystem: 'network' | 'thermal' | 'hydraulic' | 'economy' | 'behavior';
  message: string;          // Formatted event trace output
  targetEntityId: string;   // Associated Entity ID for viewport auto-focusing
}

export interface TickBatch {
  tickId: number;
  timestamp: number;
  seed: number;             // Simulation seed for verification tracking
  entities: EntityState[];
  causalEvents: CausalEvent[];
}
```