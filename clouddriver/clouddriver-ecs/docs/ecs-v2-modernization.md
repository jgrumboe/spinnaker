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
- **Read-path delegates (done):** clouddriver's read APIs and Orca's polling
  calls (`ClusterController`, `OortService#getServerGroupFromCluster`,
  `#getTargetServerGroup`, etc.) select a `ClusterProvider` /
  `LoadBalancerProvider` bean by an *exact string match* on
  `getCloudProviderId()` / `getCloudProvider()`. The original
  `EcsServerClusterProvider` and `EcsLoadBalancerProvider` hardcode
  `EcsCloudProvider.ID` ("ecs"), so without a counterpart, a deploy declaring
  `cloudProvider: "ecs-native"` would create/update the ECS service
  successfully and then find **zero** matching providers when Orca resolves
  the target server group afterwards — the stage would hang or fail even
  though ECS is healthy. `EcsNativeServerClusterProvider` and
  `EcsNativeLoadBalancerProvider` are pure delegates (forward every method to
  the existing bean, override only the id) that close this gap without
  touching the original classes.
- **Full sweep of the same bug class (done):** every `get*()`-style provider in
  `clouddriver-ecs` was checked for the same "controller selects a bean by an
  exact match on a hardcoded `EcsCloudProvider.ID`" pattern. Fixed with the
  same pure-delegate approach: `EcsNativeSubnetProvider` (`SubnetController`),
  `EcsNativeSecurityGroupProvider` (`SecurityGroupController`),
  `EcsNativeRoleProvider` (`RoleController`), `EcsNativeInstanceProvider`
  (`InstanceController`, optional filter). Checked and found *not* to need a
  delegate: `EcsApplicationProvider` (`ApplicationProvider` aggregates across
  providers, no per-id filtering), `EcsCloudMetricProvider` (doesn't actually
  implement `CloudMetricController`'s `CloudMetricProvider` interface -- its
  `getCloudProvider()` method is vestigial/unrelated), `EcrImageProvider` /
  `UnvalidatedDockerImageProvider` (`ImageRepositoryProvider` has no consumer
  in the repo that filters by cloud-provider id).
- **Global search: re-checked, no fix needed.** An earlier draft of this doc
  claimed a search filtered to `cloudProvider: "ecs-native"` would find
  nothing because cache keys are written with the `ecs;...` prefix
  (`Keys.SEPARATOR = ";"`, not `:`). That claim wasn't verified against the
  actual search code and turned out to be wrong. Tracing
  `CatsSearchProvider`/`SearchableProvider`: `EcsProvider` never overrides
  `getKeyParser()` (inherits the interface's `Optional.empty()` default), so
  `supportsSearch()`'s cloud-provider check (`getKeyParser().map(...).orElse(true)`)
  always returns `true` regardless of the requested `cloudProvider` filter
  value -- it never excludes ECS's own cache from the search, for either
  `"ecs"` or `"ecs-native"`. Separately, the generic `buildSearchTerm()` glob
  (colon-separated) doesn't match ECS's actual semicolon-separated keys
  regardless of provider id, which looks like a pre-existing quirk of the
  original `ecs` provider's search integration, unrelated to this RFC. No
  regression from `ecs-native`, and nothing to fix here.

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
   - 2c (done, standalone/no-touch): the `ecs-native` create path now supports a
     durable-service deploy. `EcsNativeCreateServerGroupAtomicOperation`
     overrides `operate()`: when the opt-in `inPlaceUpdate` flag is set and a
     deploy source exists, it rolls that existing service in place via native
     `UpdateService` (reusing the shared, `protected` `registerTaskDefinition`
     for a new revision) instead of creating a new versioned service; the first
     deploy (no source) still delegates to `super.operate()`. Implemented as a
     subclass, so the shared create operation is untouched. Role inference and
     deployment-result building are re-derived in the subclass (the originals
     are `private`).
   - Important constraint: `inPlaceUpdate` must be paired with a no-op / native
     deployment strategy. It must NOT be combined with red/black, which would
     disable and destroy the service that was just updated. Wiring a native
     (strategy=none) deploy in orca/deck is the follow-up that makes this safe
     to select in the UI.
   - SDK v2 upgrade.
3. **Observability (done, compiler-verified):** clouddriver gets a standalone,
   additive `EcsNativeServiceDeploymentController` (`GET
   /ecs-native/serverGroups/{account}/{region}/{serverGroupName}/deploymentStatus`)
   that calls live `DescribeServices` and maps the service's `PRIMARY`
   `Deployment` (`rolloutState`, `rolloutStateReason`, task counts) onto
   `EcsServiceDeploymentStatus` -- not cache-backed, since a polling wait task
   needs current state. Orca gets a matching standalone `EcsNativeService`
   retrofit client (`DelegatingEcsNativeService`, one new `@Bean` method
   alongside the existing `oortDeployService` in `CloudDriverConfiguration`
   -- the only shared file touched, additive-only), `WaitForEcsNativeServiceDeploymentTask`
   (maps `IN_PROGRESS` -> RUNNING, `COMPLETED` -> SUCCEEDED, `FAILED` -> TERMINAL,
   which also covers a circuit-breaker rollback), and a minimal
   `WaitForEcsNativeServiceDeploymentStage` wrapping it. The stage is
   deliberately *not* wired into the shared deploy stage's task graph -- users
   add it explicitly after an `ecs-native` deploy/clone stage, the same way
   `ecs-native` itself is selected today (via pipeline JSON) until deck lands.
   Once network access allowed a real `:clouddriver:clouddriver-ecs:compileJava`
   run against the actual pinned `aws-java-sdk-ecs:1.12.261` jar, `Deployment`'s
   fields (`rolloutState`, `rolloutStateReason`, `desiredCount`, `runningCount`,
   `pendingCount`, `failedTasks`, `createdAt`, `updatedAt` -- no `clusterArn`,
   confirming the earlier design choice to source that from the cached service
   instead) matched exactly what this phase assumed. Compiled clean.
4. **Blue/green + alarms:**
   - **Deployment alarms: attempted, then reverted -- unsupported by the pinned
     SDK.** The first pass added `alarmNames`/`enableDeploymentAlarms`/
     `deploymentAlarmsRollback` and wired a `DeploymentAlarms` onto
     `DeploymentConfiguration.alarms`, on the assumption (stated in an earlier
     draft of this doc) that alarms were "long-stable" like the circuit
     breaker. Compiling against the real jar proved that wrong: decompiling
     `DeploymentConfiguration` from `aws-java-sdk-ecs:1.12.261` shows it has
     only `deploymentCircuitBreaker`, `minimumHealthyPercent`, and
     `maximumPercent` -- no `alarms` field, and `DeploymentAlarms` doesn't
     exist anywhere in that jar. Reverted cleanly (fields, wiring, and tests
     all removed) rather than leave a feature that looks configurable but
     silently does nothing. Same category of gap as blue/green below: needs a
     newer `aws-java-sdk-ecs` (or the SDK v2 upgrade) before it can be
     implemented at all.
   - **Native blue/green controller, bake time, lifecycle hooks: not
     attempted.** AWS's native (non-CodeDeploy) ECS blue/green deployment
     strategy is a very recent addition (announced re:Invent 2024); given
     alarms support (added earlier than blue/green) is already missing from
     `aws-java-sdk-ecs:1.12.261`, blue/green is essentially certain to be
     missing too. Recommend doing the SDK v2 upgrade first (or at minimum
     bumping `aws-java-sdk-ecs` to a version that has `DeploymentAlarms`, which
     would also unblock the alarms feature above), then revisiting both with a
     working compiler to confirm the real request/response shapes.
   - Optional Kayenta hook: not attempted; depends on the above.
5. **Deck polish:** native strategy registry entries and wizard fields for the
   new deployment configuration.

**Verification note:** network access was restored partway through this work.
Both `clouddriver-ecs` and `orca-clouddriver` now compile clean via the
composite-build task paths (`clouddriver` and `orca` are `includeBuild`, not
plain subprojects, so `:clouddriver-ecs:...`/`:orca-clouddriver:...` alone
don't resolve from the repo root -- use `":clouddriver:clouddriver-ecs:..."` /
`":orca:orca-clouddriver:..."`), against the actual pinned dependency
versions including `aws-java-sdk-ecs:1.12.261`. The Gradle plugin portal is
still intermittently rate-limited (429) in this sandbox; retrying the same
command a handful of times gets through it.

Running `./gradlew ":clouddriver:clouddriver-ecs:test" --tests '*EcsNative*'`
for real caught a genuine bug the compile alone couldn't: `CreateServerGroupDescription`
overrides `getRegion()` unconditionally to derive it from
`getAvailabilityZones().keySet()` (there's no way to reach the plain `region`
field on `AbstractECSDescription` once that override exists -- every
`getRegion()` call resolves to it, however it's dispatched). The in-place-update
path never populates `availabilityZones` (it doesn't need it -- it isn't
creating anything), so `getAmazonEcsClient()`, `buildDeploymentResult()`, and
anything else that calls the inherited `getRegion()` threw a
`NullPointerException` for any in-place-update request that didn't happen to
also set availability zones. Fixed with a `getRegion()` override in
`EcsNativeCreateServerGroupAtomicOperation`, scoped strictly to
`isInPlaceUpdate()`, that resolves from `description.getSource().getRegion()`
instead -- always populated whenever `resolveExistingServiceName()` finds an
existing service, since both come from the same deploy-stage source block.
Deliberately not applied to normal clone flows, in-place or not, where
`source.region` can legitimately differ from the destination
`availabilityZones` region (e.g. a cross-region clone). All 24 `*EcsNative*`
tests in `clouddriver-ecs` pass after the fix.

`orca-clouddriver`'s `*EcsNative*` tests have not yet been run (only compiled)
-- run `./gradlew ":orca:orca-clouddriver:test" --tests '*EcsNative*'` to
verify them.

## 7. Open questions

- Resolved: is the dash in `ecs-native` a problem anywhere (Java identifiers,
  enum constants, Spring bean names, URL path matching, config binding)? No —
  checked every place the id is consumed: `AnnotationsBasedAtomicOperationsRegistry`
  and Orca's `ServerGroupCreator` dispatch both match it via plain
  `String.equals()`, and clouddriver's `@PathVariable String cloudProvider`
  routes have no regex constraint (dashes are valid URL path characters). It's
  never used as a Java identifier, enum constant, or delimiter-split token, so
  no rename is needed.
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
