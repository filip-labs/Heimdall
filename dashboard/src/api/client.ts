export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); this.name = 'ApiError'; }
}
export function problemMessage(status: number, body: unknown): string {
  if (status >= 500) return 'The control plane could not complete this request. Please retry.';
  if (body && typeof body === 'object' && 'detail' in body && typeof body.detail === 'string') return body.detail.slice(0, 400);
  return status === 404 ? 'This record was not found.' : `Request failed (${status}). Please refresh and retry.`;
}
export async function request<T>(path: string, signal?: AbortSignal, body?: unknown): Promise<T> {
  let response: Response;
  try { response = await fetch(`/api/v1${path}`, { signal, method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? undefined : {'Content-Type':'application/json'}, body: body === undefined ? undefined : JSON.stringify(body) }); }
  catch (error) { if (signal?.aborted) throw error; throw new Error('Cannot reach the control plane. Check the backend connection and retry.'); }
  if (!response.ok) { const problem: unknown = await response.json().catch(() => null); throw new ApiError(response.status, problemMessage(response.status, problem)); }
  return response.json() as Promise<T>;
}
