# RFC: Modernizing Spinnaker's Amazon ECS support (ECS v2)

Status: Draft / for discussion
Scope: clouddriver, orca, deck
Author: (proposal)

## 1. Summary

Spinnaker's Amazon ECS provider was built in 2018 by mapping Netflix's EC2
mental model (clusters + versioned server groups) onto ECS. Since then AWS has
turned an ECS *deployment* into a first-class, observable resource with native
rolling updates, deployment circuit breakers with automatic rollback,
deployment alarms, and — more recently — native blue/green deployments inside
the ECS deployment controller (no CodeDeploy required).

This document describes the gap between what Spinnaker does today and what ECS
now offers natively, and proposes an "ECS v2" integration that treats the ECS
Service as the durable unit and delegates rollout mechanics to ECS's native
deployment lifecycle. It is designed to ship incrementally and to coexist with
the current provider rather than replace it in one step.

## 2. Current state ("v1")

### 2.1 Conceptual mapping

| Spinnaker concept        | ECS mapping today                                  | Where |
|--------------------------|----------------------------------------------------|-------|
| Cluster                  | Task-definition family `app-stack-detail`          | `names/EcsServerGroupName.getFamilyName()` |
| Server group             | A whole **ECS Service** `app-stack-detail-vNNN`    | `names/EcsServerGroupName.getServiceName()` |
| New version (v001→v002)  | **A brand-new ECS Service**                        | `deploy/ops/CreateServerGroupAtomicOperation` |
| Deploy strategy          | Orca synthetic stages disable/shrink/destroy old   | `RedBlackStrategy`, `HighlanderStrategy` |

Version resolution (`names/EcsServerGroupNameResolver`) lists all services in
the ECS cluster, reads each service's Moniker from its tags, and picks the next
free sequence number in a 0–999 namespace.

### 2.2 How a deploy actually runs

1. Deck wizard (`deck/packages/ecs`) collects the command; ECS registers only
   `['redblack']` as an allowed deployment strategy (`ecs.module.ts`).
2. Orca's generic `AbstractDeployStrategyStage` resolves the strategy name and
   composes synthetic stages; `EcsServerGroupCreator` passes the context
   straight through to clouddriver (no ECS-specific deploy logic in orca).
3. Clouddriver `CreateServerGroupAtomicOperation`:
   `RegisterTaskDefinition` → `CreateService` (a **new** service `…-vNNN`) →
   `registerScalableTarget` → optionally copy scaling policies.
4. Orca strategy stages then `disableCluster` (set `desiredCount=0`, wait a
   hard-coded minimum of 90s for connection draining via
   `WaitForClusterDisableTask`) and `shrinkCluster`/`destroy` the old service.

### 2.3 Native ECS features currently used — and not

Used (minimally):
- Default `ECS` rolling controller (never set explicitly, so it defaults).
- `DeploymentConfiguration` with **hard-coded** `minimumHealthyPercent=100`,
  `maximumPercent=200` (`CreateServerGroupAtomicOperation`, ~line 536).
- `DeploymentCircuitBreaker` — `enable` is user-toggleable
  (`enableDeploymentCircuitBreaker`), but `.withRollback(false)` is
  **hard-coded off**.

Not used at all (verified by repo-wide grep in `clouddriver-ecs`):
- `deploymentController` is never set → no native blue/green, no `EXTERNAL`,
  no `CODE_DEPLOY`.
- No deployment alarms.
- No observation of the native ECS deployment resource
  (`ListServiceDeployments` / `DescribeServiceDeployments`).
- `UpdateServiceAndTaskConfigAtomicOperation` and `BasicEcsDeployHandler` are
  **unimplemented stubs** (`// TODO`). There is no in-place service update
  path; every rollout is a new service.
- Still on **AWS SDK v1** (`com.amazonaws.services.ecs`), which is end-of-life.

### 2.4 Cost of the v1 model

- New ECS Service per version → churn on target-group registration,
  service-discovery registration, and autoscaling-target creation.
- Orphaned/draining services and a fixed 90s drain wait regardless of the
  service's real health signal.
- Spinnaker re-implements, at the cluster level, an orchestration (red/black)
  that ECS now performs natively inside one durable service.
- Rollback = re-enable the old service, rather than the native ECS rollback.

## 3. What AWS ECS offers natively now

- **Deployment circuit breaker with automatic rollback** on the rolling
  controller.
- **Deployment alarms** — bind CloudWatch alarms to trigger rollback.
- **Native blue/green in the ECS deployment controller** — traffic shifting,
  bake time, and lifecycle hook Lambdas, without CodeDeploy.
- **A first-class, queryable deployment lifecycle** — `ListServiceDeployments`,
  `DescribeServiceDeployments`, `StopServiceDeployment` — so a rollout has a
  real status (`IN_PROGRESS`, `SUCCESSFUL`, `ROLLBACK_*`, `STOPPED`) instead of
  being inferred from instances draining.

## 4. Proposed "ECS v2" design

Core thesis: **the ECS Service is the durable unit; the rollout is delegated to
ECS's native deployment lifecycle.**

### 4.1 Durable service + revision model

- A v2 "server group" maps to a *service revision / deployment* of one
  long-lived ECS service, not a new service per version.
- Deploy = `RegisterTaskDefinition` + `UpdateService` (implement the stubbed
  update path). ECS produces a `serviceDeployment` that Spinnaker tracks.
- Eliminates per-version churn of target groups, service discovery, and
  scaling targets.

### 4.2 First-class deployment configuration (end-to-end)

Surface these from description → orca passthrough → deck:
- `deploymentController`: `ECS` rolling or native blue/green.
- Configurable `minimumHealthyPercent` / `maximumPercent` (stop hard-coding).
- Circuit breaker with **rollback** exposed as a real toggle.
- **Deployment alarms** — allow a pipeline to bind CloudWatch alarms (or a
  Kayenta canary verdict) to native rollback.
- Blue/green knobs: bake time, lifecycle hook Lambda ARNs.

### 4.3 Deployment-aware status in orca

- New `WaitForEcsServiceDeploymentTask` polls `DescribeServiceDeployments` and
  maps native states to stage status — replacing the instances-draining
  heuristic and the fixed 90s wait.
- Rollback becomes `StopServiceDeployment` / update-to-previous-revision,
  rather than re-enabling an old service.

### 4.4 New deployment strategies

Register for ECS in the deck strategy registry:
- `ecsNativeRolling` (rolling + circuit breaker + alarms)
- `ecsBlueGreen` (native ECS blue/green)
- Keep `redblack` / `highlander` working for backward compatibility.

### 4.5 AWS SDK v2

Move the ECS client to AWS SDK v2 — required for the newer deployment APIs and
because SDK v1 is EOL.

## 5. Migration & coexistence

Do **not** replace the v1 provider in one step. Add v2 behind an
account/service-level flag so existing pipelines and cached server groups keep
working. Phased delivery:

1. **Foundation (low risk, immediately useful):** implement
   `UpdateServiceAndTaskConfig`; make `DeploymentConfiguration` fully
   configurable (min/max %, circuit-breaker rollback).
2. **Observability:** SDK v2 upgrade + `WaitForEcsServiceDeployment` task
   reading the native deployment resource.
3. **Native strategies:** register `ecsNativeRolling` / `ecsBlueGreen`; wire
   deployment alarms; optional Kayenta hook.
4. **Deck:** expose controller/percent/rollback/alarm fields; register the new
   strategies; keep `redblack` for legacy.

## 6. Key files impacted

- `clouddriver-ecs/.../deploy/ops/UpdateServiceAndTaskConfigAtomicOperation.java` (implement stub)
- `clouddriver-ecs/.../deploy/ops/CreateServerGroupAtomicOperation.java` (~536: un-hard-code deployment config)
- `clouddriver-ecs/.../deploy/description/CreateServerGroupDescription.java` (new fields)
- new clouddriver op/description to observe `serviceDeployments`
- `orca/.../tasks/providers/ecs/EcsServerGroupCreator.groovy` + new `WaitForEcsServiceDeploymentTask`
- `deck/packages/ecs/.../wizard/pages/AdvancedSettings.tsx`, `ecs.module.ts`, new strategy defs under `deck/packages/core/src/deploymentStrategy/strategies/`

## 7. Open questions

- Backward compatibility of caching/indexing when a "server group" is a service
  revision rather than a distinct service (impacts the deck clusters view).
- How native blue/green traffic shifting interacts with Spinnaker's existing
  load-balancer/target-group model.
- Whether to bridge native deployment alarms to Kayenta, or keep them separate.
- Fargate vs EC2 capacity-provider behavior differences under native blue/green.
- Rollback semantics surfaced to the user when ECS auto-rolls-back mid-stage.
