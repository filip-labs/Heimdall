import type { Stage } from "../types/api";
import { orderedStages } from "../utils/domain";
import { Status, Timestamp } from "./ui";
export function StageTrail({
  stages,
  horizontal = false,
}: {
  stages: Stage[];
  horizontal?: boolean;
}) {
  const ordered = orderedStages(stages);
  return (
    <ol
      className={`stage-trail ${horizontal ? "horizontal" : ""}`}
      aria-label="Cumulative rollout stages"
    >
      {ordered.map((s, i) => (
        <li key={s.id} className={s.status.toLowerCase()}>
          <span className="stage-marker" aria-hidden="true">
            {s.status === "COMPLETED"
              ? "✓"
              : s.status === "FAILED"
                ? "×"
                : i + 1}
          </span>
          <div>
            <strong>{s.targetPercentage}%</strong>
            <Status value={s.status} />
          </div>
          <div className="stage-count">
            <span>{s.targetVehicleCount} cumulative vehicles</span>
            <small className="muted">
              +
              {s.targetVehicleCount - (ordered[i - 1]?.targetVehicleCount ?? 0)}{" "}
              in this cohort
            </small>
            {!horizontal && s.startedAt && (
              <small className="muted">
                <Timestamp value={s.startedAt} />
              </small>
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}
