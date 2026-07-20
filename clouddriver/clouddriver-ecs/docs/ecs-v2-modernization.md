# RFC: A second, native ECS deployment provider (`ecs-native`)

Status: Draft / for discussion
Scope: clouddriver, orca, deck
Author: (proposal)

## 1. Summary

Spinnaker's existing Amazon ECS provider (`ecs`) models deployments on Netflix's
EC2 concepts: a "server group" is a whole ECS Service (`app-stack-detail-vNNN`),
every deploy creates a brand-new service, and rollout strategy (red/black,
highlander) is orchestrated by Orca disabling/destroying the old service. It
does not use ECS's native deployment lifecycle (rolling + circuit breaker with
rollback, deployment alarms, native blue/green, observable service
deployments).

Rather than change the existing provider, this RFC proposes a **second,
opt-in provider — `ecs-native`** — that keeps the ECS Service as the durable
unit and delegates rollout to ECS's native deployment lifecycle. Users choose
it **per pipeline** by selecting the `ecs-native` deploy/clone stage.

Design constraints agreed:
- The existing `ecs` provider is **not modified**. It remains the default.
- `ecs-native` adds a **new write/deploy path only**; it **reuses** the existing
  ECS accounts, caching agents, and cluster/load-balancer/instance **views**.
- Selection is **per pipeline / per stage** via the cloud-provider id.

## 2. Why a second provider (not a rewrite)

- Zero risk to existing ECS users; no behavior change unless a pipeline opts in.
- Spinnaker keys deploy stages by `cloudProvider`, so a distinct provider id
  gives per-pipeline choice for free: adding a Deploy/Clone stage lets the user
  pick "Amazon ECS (native)".
- The running resources are the *same* ECS objects, so duplicating the entire
  read/caching/view stack is wasteful. `ecs-native` differs from `ecs` only in
  *how it rolls out*, so only the write path is new.

## 3. Current `ecs` provider (baseline)

| Spinnaker concept        | `ecs` mapping today                                | Where |
|--------------------------|----------------------------------------------------|-------|
| Cluster                  | Task-definition family `app-stack-detail`          | `names/EcsServerGroupName.getFamilyName()` |
| Server group             | A whole ECS Service `app-stack-detail-vNNN`        | `names/EcsServerGroupName.getServiceName()` |
| New version (v001→v002)  | A **brand-new** ECS Service                        | `deploy/ops/CreateServerGroupAtomicOperation` |
| Deploy strategy          | Orca synthetic stages disable/shrink/destroy old   | `RedBlackStrategy`, `HighlanderStrategy` |

Native features currently unused (verified by grep in `clouddriver-ecs`):
- `deploymentController` never set → no native blue/green, no `CODE_DEPLOY`.
- `minimumHealthyPercent=100`/`maximumPercent=200` hard-coded; circuit-breaker
  `rollback` hard-coded off (`CreateServerGroupAtomicOperation`, ~line 536).
- No deployment alarms; no observation of the native `serviceDeployment`.
- `UpdateServiceAndTaskConfigAtomicOperation` and `BasicEcsDeployHandler` are
  unimplemented stubs — no in-place update path.
- AWS SDK v1 (EOL).

## 4. `ecs-native` provider design

Core thesis: **the ECS Service is durable; the rollout is delegated to ECS's
native deployment lifecycle.**

### 4.1 What is new (write path)

- **In-place deploy:** `RegisterTaskDefinition` + `UpdateService` against a
  long-lived service, producing a native `serviceDeployment` Spinnaker tracks.
  New services are only created on first deploy.
- **First-class deployment configuration** exposed end-to-end:
  - `deploymentController`: `ECS` rolling or native blue/green.
  - Configurable `minimumHealthyPercent` / `maximumPercent`.
  - Circuit breaker with a real **rollback** toggle.
  - **Deployment alarms** — bind CloudWatch alarms (or a Kayenta verdict) to
    native rollback.
  - Blue/green: bake time, lifecycle hook Lambda ARNs.
- **Deployment-aware status:** a new Orca `WaitForEcsServiceDeploymentTask`
  polls `DescribeServiceDeployments` and maps native states (`IN_PROGRESS`,
  `SUCCESSFUL`, `ROLLBACK_*`, `STOPPED`) to stage status — replacing the
  instances-draining heuristic and the fixed 90s wait.
- **Rollback** = `StopServiceDeployment` / update-to-previous-revision, not
  re-enabling an old service.
- **AWS SDK v2** for the ECS client (needed for the newer deployment APIs).

### 4.2 What is reused (read path)

- **Accounts:** none are duplicated. Clouddriver resolves an operation's
  account purely by name (`AbstractAtomicOperationsCredentialsSupport
  .getCredentialsObject(name)` ignores the cloud provider), so an `ecs-native`
  stage simply targets an existing ECS account by name. No `ecs-native`
  credentials/config subsystem is required, and no operator config is needed to
  "enable" accounts for the new provider. (This supersedes the earlier
  opt-in-accounts idea, which is unnecessary given name-based resolution.)
- **Caching:** no new caching agents. Existing `ecs` agents already cache all
  ECS services/clusters/task-defs in the account regardless of who created
  them.
- **Views:** cluster / load-balancer / instance / details views are served by
  the existing `ecs` view providers. Because the deploy reuses the existing
  ECS credentials (whose `cloudProvider` is `ecs`), the resulting services show
  up in today's ECS clusters UI — `ecs-native` is purely a deploy-time routing
  concept.

### 4.2.1 How dispatch actually routes to the new path

`AnnotationsBasedAtomicOperationsRegistry` selects a converter by the cloud
provider's operation-annotation type plus the operation name. So a stage with
`cloudProvider: "ecs-native"` resolves `EcsNativeCloudProvider` →
`@EcsNativeOperation`, then matches the `@EcsNativeOperation` converter for that
operation. The existing `@EcsOperation` converters are never matched for
`ecs-native` (and vice versa), so the two providers are fully isolated at the
dispatch layer with no change to existing code. The `ecs-native` converters
must **not** subclass the `@EcsOperation` converters: Spring's
`getBeansWithAnnotation` walks superclasses, which would leak a child into the
`ecs` matches and break the existing provider — they are standalone classes.

### 4.3 Provider registration points

clouddriver (done for Phase 1):
- `EcsNativeCloudProvider` (`id = "ecs-native"`) bean.
- `@EcsNativeOperation` annotation + a full set of standalone converters
  (`deploy/converters/ecsnative/`) so `createServerGroup` / `cloneServerGroup` /
  `resizeServerGroup` / enable / disable / destroy / start / stop / terminate /
  scaling-policy for `ecs-native` route to operations. Phase 1 reuses the
  existing ECS descriptions/operations; native semantics diverge in later
  phases.
- No new credentials (see 4.2).

orca (creator done for Phase 1):
- `EcsNativeServerGroupCreator extends EcsServerGroupCreator`, overriding only
  `getCloudProvider()`.
- New `WaitForEcsServiceDeploymentTask` (later phase).

deck:
- New `deck/packages/ecs-native` (or a submodule of `ecs`) registering the
  cloud provider and the deploy/clone **stages + wizard** for `ecs-native`,
  reusing the existing ECS details/transformers for the read side.
- Register any native strategies (`ecsNativeRolling`, `ecsBlueGreen`) restricted
  to `ecs-native` in the deployment-strategy registry.

## 5. Per-pipeline selection (how the user picks it)

- In a pipeline, adding a **Deploy** or **Clone Server Group** stage offers the
  provider chooser; `ecs-native` appears as "Amazon ECS (native)" and targets
  the same accounts.
- The stage stores `cloudProvider: "ecs-native"`, so Orca resolves the
  `EcsNativeServerGroupCreator` and clouddriver routes to the native
  operations — while an `ecs` stage in another pipeline is unaffected.
- Existing ECS pipelines keep using `ecs` with no change.

## 6. Phased delivery

1. **Provider skeleton:** `ecs-native` cloud provider + operation
   annotation/converters + orca creator (all reusing existing ecs
   accounts/descriptions/operations), then deck provider registration + a
   create/clone stage that initially mirrors `ecs` behavior. The clouddriver +
   orca half is complete; deck registration is the remaining piece. Until deck
   lands, `ecs-native` is selectable by setting `cloudProvider: "ecs-native"`
   on a deploy/clone stage in pipeline JSON.
2. **Native rollout core:**
   - 2a (done): fully-configurable `DeploymentConfiguration` — `minimumHealthyPercent`
     / `maximumPercent` and circuit-breaker `rollback` — via
     `EcsNativeCreateServerGroupDescription` +
     `EcsNativeCreateServerGroupAtomicOperation`, which overrides only the
     already-`protected` `makeServiceRequest` (calls `super`, then replaces the
     `DeploymentConfiguration`). Zero changes to existing `ecs` code; unset
     fields preserve today's 100/200 + no-rollback behavior.
   - 2b (done, additive/no-touch): in-place `UpdateService` as a standalone
     `EcsNativeUpdateServiceAtomicOperation` (+ `EcsNativeUpdateServiceDescription`),
     wired to the `ecs-native` `UPDATE_LAUNCH_CONFIG` converter (which for the
     original provider is an empty stub). It rolls an existing durable service
     to a new task definition and/or native deployment configuration
     (min/max %, circuit breaker + rollback) and can force a new rolling
     deployment. Spock spec covers the configured and no-config paths.
   - 2c (pending, needs a decision): make the `ecs-native` *create/deploy* path
     use a single durable service (update-in-place on redeploy) instead of a
     new versioned `…-vNNN` service per deploy. This cannot be done zero-touch —
     the create operation's `operate()`, `createService`,
     `buildEcsServerGroupName`, and `inferAssumedRoleArn` are `private`, and the
     naming currently produces versioned names — so it requires either a
     behavior-preserving visibility widening (private → protected) of those
     seams, or a standalone deploy operation that duplicates that logic.
   - SDK v2 upgrade.
3. **Observability:** `WaitForEcsServiceDeploymentTask` reading the native
   deployment resource; map states to stage status/rollback.
4. **Blue/green + alarms:** native blue/green controller, bake time, lifecycle
   hooks, deployment alarms; optional Kayenta hook.
5. **Deck polish:** native strategy registry entries and wizard fields for the
   new deployment configuration.

## 7. Open questions

- Account exposure is resolved: no separate accounts (name-based resolution,
  see 4.2). Open sub-question: how should deck populate the `ecs-native` stage's
  account dropdown — reuse the `ecs` account list directly, or expose the
  accounts under `ecs-native` in `/credentials`?
- Deck: separate `packages/ecs-native` vs. a mode inside `packages/ecs` that
  reuses most components — how much UI to share.
- Native blue/green traffic shifting vs. Spinnaker's existing target-group
  model.
- How ECS auto-rollback mid-deploy is surfaced as stage status in the UI.
- Fargate vs EC2 capacity-provider behavior differences under native
  blue/green.

## 8. Key files (for reference; new code lives in new classes/modules)

Existing `ecs` (read/reused, **unchanged**):
- `clouddriver-ecs/.../provider/agent/*CachingAgent.java`, `provider/view/*`
- `clouddriver-ecs/.../names/EcsServerGroupName*.java`

New `ecs-native` (write path):
- clouddriver: new `EcsNativeCloudProvider`, operation annotation + converters,
  `deploy/ops/*` (create/clone/resize/update via native lifecycle),
  credentials wrapper over existing ECS accounts.
- orca: `EcsNativeServerGroupCreator`, `WaitForEcsServiceDeploymentTask`.
- deck: `packages/ecs-native` provider + deploy/clone stage & wizard; strategy
  defs under `packages/core/src/deploymentStrategy/strategies/`.
