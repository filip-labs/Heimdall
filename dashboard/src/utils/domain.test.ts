import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  distribution,
  fleetCounts,
  controls,
  stagePlan,
  failureRate,
  stageMetrics,
  orderedStages,
} from "./domain";
import { problemMessage } from "../api/client";
import { time } from "./format";
import type { Stage } from "../types/api";
const stage = (
  stageIndex: number,
  targetVehicleCount: number,
  installedCount: number,
  failedCount: number,
): Stage => ({
  id: String(stageIndex),
  stageIndex,
  targetVehicleCount,
  targetPercentage: 100,
  status: "COMPLETED",
  deploymentCount: installedCount + failedCount,
  installedCount,
  failedCount,
  startedAt: null,
  completedAt: null,
});
describe("fleet metrics", () => {
  it("accounts for every registered vehicle including offline versions", () => {
    assert.deepEqual(
      fleetCounts([
        { connectivityStatus: "ONLINE" },
        { connectivityStatus: "OFFLINE" },
      ]),
      { total: 2, online: 1, offline: 1 },
    );
    const mix = distribution([
      { softwareVersion: "2.0" },
      { softwareVersion: "1.0" },
      { softwareVersion: "2.0" },
    ]);
    assert.equal(mix[0].count, 2);
    assert.ok(Math.abs(mix.reduce((n, v) => n + v.percent, 0) - 100) < 0.001);
  });
  it("handles an empty fleet", () => {
    assert.deepEqual(distribution([]), []);
    assert.deepEqual(fleetCounts([]), { total: 0, online: 0, offline: 0 });
  });
  it("keeps 100-vehicle distribution totals accurate", () => {
    const fleet = Array.from({ length: 100 }, (_, i) => ({
      softwareVersion: i < 65 ? "1.0.0" : "2.0.0",
    }));
    assert.deepEqual(distribution(fleet), [
      { version: "1.0.0", count: 65, percent: 65 },
      { version: "2.0.0", count: 35, percent: 35 },
    ]);
  });
});
describe("rollout semantics", () => {
  it("never resumes a failed or unknown health gate", () => {
    assert.deepEqual(controls("PAUSED", { status: "FAILED" }), {
      pause: false,
      resume: false,
      cancel: true,
    });
    assert.equal(controls("PAUSED").resume, false);
    assert.equal(controls("PAUSED", { status: "RUNNING" }).resume, true);
  });
  it("allows controls only in valid states", () => {
    assert.equal(controls("RUNNING").pause, true);
    for (const state of ["COMPLETED", "CANCELLED"] as const)
      assert.deepEqual(controls(state, { status: "COMPLETED" }), {
        pause: false,
        resume: false,
        cancel: false,
      });
  });
  it("validates cumulative stage targets", () => {
    assert.deepEqual(stagePlan("5,25,50,100"), [5, 25, 50, 100]);
    for (const s of [
      "5,5,100",
      "50,25,100",
      "0,100",
      "25,50",
      "",
      "5,,100",
      "5.5,100",
    ])
      assert.equal(stagePlan(s), null);
  });
  it("uses incremental deployment counts, never sums cumulative target counts", () => {
    const stages = [stage(1, 25, 19, 1), stage(0, 5, 5, 0)];
    assert.deepEqual(stageMetrics(stages), {
      installed: 24,
      failed: 1,
      deployed: 25,
      failureRate: 4,
    });
    assert.deepEqual(
      orderedStages(stages).map((s) => s.stageIndex),
      [0, 1],
    );
    assert.equal(stages[0].stageIndex, 1);
  });
  it("uses all stage deployments as health denominator", () => {
    assert.equal(failureRate(1, 5), 20);
    assert.equal(failureRate(0, 0), 0);
  });
});
describe("API display safety", () => {
  it("does not expose server stack traces", () => {
    assert.ok(
      !problemMessage(500, { detail: "java.lang.Exception secret" }).includes(
        "java",
      ),
    );
    assert.equal(
      problemMessage(409, { detail: "Vehicle has moved" }),
      "Vehicle has moved",
    );
  });
  it("handles nullable and malformed timestamps", () => {
    assert.equal(time(null), "Never seen");
    assert.equal(time("bad"), "Unavailable");
  });
});
