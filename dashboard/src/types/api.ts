export interface Vehicle {
  id: string;
  vin: string;
  softwareVersion: string;
  connectivityStatus: "ONLINE" | "OFFLINE";
  lastSeenAt: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface Release {
  id: string;
  version: string;
  description: string | null;
  artifactUrl: string;
  checksum: string;
  signatureAlgorithm: string | null;
  signingKeyId: string | null;
  signature: string | null;
  createdAt: string;
}
export interface CreateRelease {
  version: string;
  description: string;
  artifactUrl: string;
  checksum: string;
}
export type RolloutStatus = "RUNNING" | "PAUSED" | "COMPLETED" | "CANCELLED";
export type StageStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
export interface Rollout {
  id: string;
  releaseId: string;
  targetSoftwareVersion: string;
  status: RolloutStatus;
  failureThresholdPercent: number;
  automaticRollbackEnabled: boolean;
  totalVehicles: number;
  currentStageIndex: number;
  createdAt: string;
  updatedAt: string;
}
export interface Stage {
  id: string;
  stageIndex: number;
  targetPercentage: number;
  targetVehicleCount: number;
  status: StageStatus;
  deploymentCount: number;
  installedCount: number;
  failedCount: number;
  startedAt: string | null;
  completedAt: string | null;
}
export interface CreateRollout {
  releaseId: string;
  stages: number[];
  failureThresholdPercent: number;
  automaticRollbackEnabled: boolean;
}
export type DeploymentStatus =
  | "PENDING"
  | "DOWNLOADING"
  | "DOWNLOADED"
  | "INSTALLING"
  | "INSTALLED"
  | "FAILED";
export interface Deployment {
  id: string;
  vehicleId: string;
  vin: string;
  sourceSoftwareVersion: string;
  releaseId: string;
  targetSoftwareVersion: string;
  artifactUrl: string;
  checksum: string;
  signatureAlgorithm: string | null;
  signingKeyId: string | null;
  signature: string | null;
  status: DeploymentStatus;
  failureReason: string | null;
  rollbackOfDeploymentId: string | null;
  rolloutId: string | null;
  rolloutStageId: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface DeploymentEvent {
  id: string;
  fromStatus: DeploymentStatus | null;
  toStatus: DeploymentStatus;
  failureReason: string | null;
  createdAt: string;
}
