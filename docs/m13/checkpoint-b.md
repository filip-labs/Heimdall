# M13B checkpoint

Validated against a real isolated backend on port 8081 and the authenticated Go
simulator (100 vehicles, signed artifact downloads). No mocked runtime data.

- Create Release succeeded through UI in M13A; planning/review/start succeeded through UI in M13B.
- 2.1.0 canary: 5 cumulative targets, 4 installed, VIN 003 failed during installation; backend paused at 20% > 5% threshold; subsequent stages stayed pending.
- Failed-stage Resume correctly absent; Cancel available.
- Automatic rollback created four recovery deployments. Original and recovery links inspected.
- Separate operator-paused 2.2.0 rollout: Resume → Pause → Cancel all executed through confirmation dialogs and backend refresh; cancelled rollout hides controls.
- Manual rollback executed through UI on installed 2.2.0 deployment, navigated to real 2.2.0 → 1.0.0 recovery with original deployment link.
- Failed deployment timeline contained five actual chronological events: PENDING, DOWNLOADING, DOWNLOADED, INSTALLING, FAILED. Failure reason matched simulator.
- Nullable rolloutId / rolloutStageId added to DeploymentResponse only. Existing integration tests assert list/detail membership and null membership for standalone deployment. No API endpoint, domain, security or CORS changes.
- Frontend strict typecheck, lint, 10 deterministic Node tests, build passed.
- Full Java suite: 116 passed. Go and race: passed. M10B: 33 passed; M10C: 48 passed on rerun with local JVM attach access; load harness: 8 passed.

Visual polish, screenshot capture and the repeatable fixture script are M13C work.
