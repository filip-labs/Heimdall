import { request } from "./client";
import type { Rollout, Stage, CreateRollout } from "../types/api";
export type RolloutAction = "pause" | "resume" | "cancel";
export const rollouts = {
  list: (s?: AbortSignal) => request<Rollout[]>("/rollouts", s),
  get: (id: string, s?: AbortSignal) =>
    request<Rollout>(`/rollouts/${encodeURIComponent(id)}`, s),
  stages: (id: string, s?: AbortSignal) =>
    request<Stage[]>(`/rollouts/${encodeURIComponent(id)}/stages`, s),
  create: (body: CreateRollout) =>
    request<Rollout>("/rollouts", undefined, body),
  control: (id: string, action: RolloutAction) =>
    request<Rollout>(
      `/rollouts/${encodeURIComponent(id)}/${action}`,
      undefined,
      {},
    ),
};
