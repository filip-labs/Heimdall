import { request } from "./client";
import type { Vehicle } from "../types/api";
export const vehicles = {
  list: (s?: AbortSignal) => request<Vehicle[]>("/vehicles", s),
  get: (id: string, s?: AbortSignal) =>
    request<Vehicle>(`/vehicles/${encodeURIComponent(id)}`, s),
};
