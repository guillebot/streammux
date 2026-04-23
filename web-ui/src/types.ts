export type JobType = "ROUTE_APP" | "RANDOM_SAMPLER";

export type DesiredJobState = "ACTIVE" | "PAUSED" | "DELETED";

export type PayloadFormat = "JSON" | "PROTOBUF";

export type RuntimeState = string;

export type HealthState = string;

export type LeaseStatus = string;

export type EventType = string;

export interface RouteDefinition {
  routeId: string;
  filterExpression: string;
  outputTopic: string;
}

export interface RouteAppConfig {
  inputTopic: string;
  inputFormat: PayloadFormat;
  outputFormat: PayloadFormat;
  protobufSchemaSubject: string | null;
  routes: RouteDefinition[];
  streamProperties: Record<string, string>;
  serdeProperties: Record<string, string>;
}

export interface RandomSamplerConfig {
  inputTopic: string;
  outputTopic: string;
  rate: number;
  streamProperties: Record<string, string>;
}

export interface LeasePolicy {
  heartbeatIntervalSeconds: number;
  leaseDurationSeconds: number;
  claimBackoffMillis: number;
  allowFailover: boolean;
}

export interface JobDefinition {
  jobId: string;
  jobVersion: number;
  jobType: JobType;
  desiredState: DesiredJobState;
  priority: number;
  siteAffinity: string;
  leasePolicy: LeasePolicy;
  parallelism: number;
  routeAppConfig: RouteAppConfig | null;
  randomSamplerConfig: RandomSamplerConfig | null;
  labels: Record<string, string>;
  tags: string[];
  updatedAt: string;
  updatedBy: string;
}

export interface WorkerMetadata {
  workerId: string;
  topologyName: string;
  localState: string;
  attributes: Record<string, unknown>;
}

export interface LagMetrics {
  inputLag: number;
  outputRatePerSecond: number;
  processedCount: number;
}

export interface JobRuntimeStatus {
  jobId: string;
  jobVersion: number;
  state: RuntimeState;
  health: HealthState;
  lastHeartbeatAt: string | null;
  workerMetadata: WorkerMetadata | null;
  failureReason: string | null;
  lagMetrics: LagMetrics | null;
}

export interface JobLease {
  jobId: string;
  jobVersion: number;
  leaseOwnerSite: string;
  leaseOwnerInstance: string;
  leaseEpoch: number;
  status: LeaseStatus;
  leaseExpiresAt: string | null;
  lastHeartbeatAt: string | null;
}

export interface JobEvent {
  eventId: string;
  jobId: string;
  jobVersion: number;
  eventType: EventType;
  eventTime: string;
  siteId: string | null;
  instanceId: string | null;
  message: string;
  attributes: Record<string, unknown>;
}
