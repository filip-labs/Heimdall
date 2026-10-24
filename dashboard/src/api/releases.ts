import { request } from './client';
import type { Release, CreateRelease } from '../types/api';
export const releases = { list: (s?: AbortSignal) => request<Release[]>('/releases', s), get: (id: string, s?: AbortSignal) => request<Release>(`/releases/${encodeURIComponent(id)}`, s), create: (body: CreateRelease) => request<Release>('/releases', undefined, body) };
