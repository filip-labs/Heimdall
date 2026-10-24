import type { Vehicle, Stage, RolloutStatus, Deployment } from "../types/api";
export function distribution(vehicles: Pick<Vehicle, "softwareVersion">[]) {
  const counts = new Map<string, number>();
  vehicles.forEach((v) =>
    counts.set(v.softwareVersion, (counts.get(v.softwareVersion) ?? 0) + 1),
  );
  return [...counts]
    .map(([version, count]) => ({
      version,
      count,
      percent: vehicles.length ? (count * 100) / vehicles.length : 0,
    }))
    .sort((a, b) => b.count - a.count || a.version.localeCompare(b.version));
}
export function fleetCounts(vehicles: Pick<Vehicle, "connectivityStatus">[]) {
  const online = vehicles.filter(
    (v) => v.connectivityStatus === "ONLINE",
  ).length;
  return { total: vehicles.length, online, offline: vehicles.length - online };
}
export const activeDeployment = (d: Pick<Deployment, "status">) =>
  !["INSTALLED", "FAILED"].includes(d.status);
export function controls(status: RolloutStatus, stage?: Pick<Stage, "status">) {
  return {
    pause: status === "RUNNING",
    resume: status === "PAUSED" && !!stage && stage.status !== "FAILED",
    cancel: status === "RUNNING" || status === "PAUSED",
  };
}
export function stageMetrics(stages: Stage[]) {
  const total = stages.reduce(
    (a, s) => ({
      installed: a.installed + s.installedCount,
      failed: a.failed + s.failedCount,
      deployed: a.deployed + s.deploymentCount,
    }),
    { installed: 0, failed: 0, deployed: 0 },
  );
  return { ...total, failureRate: failureRate(total.failed, total.deployed) };
}
export const failureRate = (failed: number, deployed: number) =>
  deployed ? Math.round((failed * 1000000) / deployed) / 10000 : 0;
export function stagePlan(text: string): number[] | null {
  const values = text.split(",").map((s) => Number(s.trim()));
  return values.length &&
    values.every(
      (v, i) =>
        Number.isInteger(v) &&
        v > 0 &&
        v <= 100 &&
        (i === 0 || v > values[i - 1]),
    ) &&
    values.at(-1) === 100
    ? values
    : null;
}
export const orderedStages = (stages: Stage[]) =>
  [...stages].sort((a, b) => a.stageIndex - b.stageIndex);
export const newest = <T extends { createdAt: string }>(items: T[]) =>
  [...items].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
