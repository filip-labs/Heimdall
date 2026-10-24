import { request } from './client';
import type { Deployment, DeploymentEvent } from '../types/api';
export const deployments = { list: (s?: AbortSignal) => request<Deployment[]>('/deployments', s), get: (id: string, s?: AbortSignal) => request<Deployment>(`/deployments/${encodeURIComponent(id)}`, s), events: (id: string, s?: AbortSignal) => request<DeploymentEvent[]>(`/deployments/${encodeURIComponent(id)}/events`, s), rollback: (id: string) => request<Deployment>(`/deployments/${encodeURIComponent(id)}/rollback`, undefined, {}) };
