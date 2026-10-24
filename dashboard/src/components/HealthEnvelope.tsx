import type { Rollout, Stage } from "../types/api";
import { failureRate } from "../utils/domain";
import { percent } from "../utils/format";
import { Meta } from "./ui";
export function HealthEnvelope({
  rollout,
  stage,
}: {
  rollout: Rollout;
  stage?: Stage;
}) {
  if (!stage) return <p className="muted">No stage data available.</p>;
  const rate = failureRate(stage.failedCount, stage.deploymentCount);
  const terminal =
    stage.installedCount + stage.failedCount === stage.deploymentCount;
  const scale = Math.max(rollout.failureThresholdPercent, rate, 1);
  return (
    <>
      <strong className="hero-number">{percent(rate)}</strong>
      <p className="muted small">current incremental stage failure rate</p>
      <div
        className="health-gauge"
        role="img"
        aria-label={`${percent(rate)} failed; threshold ${percent(rollout.failureThresholdPercent)}`}
      >
        <div
          style={{ width: `${Math.min(100, (rate / scale) * 100)}%` }}
          className={stage.status === "FAILED" ? "failed" : ""}
        />
        <i
          style={{
            left: `${Math.min(99, (rollout.failureThresholdPercent / scale) * 100)}%`,
          }}
        />
      </div>
      <div className="split small">
        <span>Current</span>
        <span>{percent(rollout.failureThresholdPercent)} threshold</span>
      </div>
      <hr />
      <Meta label="Health gate">
        {stage.status === "FAILED"
          ? "FAILED — rollout paused"
          : !terminal
            ? "Awaiting terminal deployments"
            : rate > rollout.failureThresholdPercent
              ? "Threshold exceeded — awaiting evaluation"
              : "Within threshold"}
      </Meta>
      <Meta label="Automatic rollback">
        {rollout.automaticRollbackEnabled ? "Enabled" : "Disabled"}
      </Meta>
      <p className={`notice ${stage.status === "FAILED" ? "danger" : ""}`}>
        {stage.failedCount} failed / {stage.deploymentCount} deployments in this
        cohort.{" "}
        {!terminal
          ? "Rate is provisional until the whole cohort is terminal."
          : "The health gate uses this cohort only."}
      </p>
    </>
  );
}
