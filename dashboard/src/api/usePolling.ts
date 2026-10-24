import { useCallback, useEffect, useRef, useState } from 'react';
export function usePolling<T>(load: (signal: AbortSignal) => Promise<T>, interval = 8000) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [revision, setRevision] = useState(0);
  const [updatedAt, setUpdatedAt] = useState<Date>();
  const loader = useRef(load); loader.current = load;
  const refresh = useCallback(() => setRevision(r => r + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      if (!document.hidden) {
        try { const result = await loader.current(controller.signal); if (!controller.signal.aborted) { setData(result); setError(undefined); setUpdatedAt(new Date()); } }
        catch (e) { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : 'Unable to load data.'); }
      }
      if (!controller.signal.aborted) timer = setTimeout(poll, interval);
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [interval, revision]);
  return {data, error, refresh, updatedAt};
}
