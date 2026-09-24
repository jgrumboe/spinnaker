# ecs-native provider — how it relates to the original `ecs` provider

> Orientation doc for anyone debugging or extending **ecs-native**. It explains what ecs-native
> is, how it reuses vs. diverges from the classic `ecs` provider, where the wiring lives across
> clouddriver / orca / deck, and the non-obvious gotchas that have already bitten us. File/line
> references are pointers — verify against the current tree before relying on them.

## TL;DR

`ecs-native` is a **second, opt-in ECS cloud provider** (`cloudProvider: "ecs-native"`) that reuses
almost all of the classic `ecs` provider's code and differs in a few deliberate ways:

| Aspect | classic `ecs` | `ecs-native` |
| --- | --- | --- |
| Deploy model | Spinnaker red/black: new versioned server group per deploy, old disabled/destroyed | Single **durable** ECS service, **updated in place** with new task-def revisions |
| Server-group / ECS service name | `app-stack-detail-vNNN` (versioned) | `app-stack-detail` (**fixed**, no `-vNNN`) |
| Who orchestrates the rollout | Spinnaker (enable/disable/scale of separate server groups) | **ECS itself** — the ECS deployment controller (ROLLING or BLUE_GREEN), incl. circuit breaker / bake time |
| Deploy completion gate | `waitForUpInstances` (instance health) | `waitForUpInstances` **plus** `waitForEcsNativeServiceDeployment` (ECS `rolloutState`) |

The single most important mental model: **ecs-native does not create a new server group per
deploy.** There is one ECS service per cluster, and deploys register a new task-definition revision
and let ECS roll it out in place. Much of the confusing behavior falls out of this one fact.

---

## 1. Provider registration & operation routing (clouddriver)

Two `CloudProvider` beans exist side by side:

- `EcsCloudProvider` — `ID = "ecs"`, `operationAnnotationType = EcsOperation.class`
- `EcsNativeCloudProvider` — `ID = "ecs-native"`, `operationAnnotationType = EcsNativeOperation.class`

(`clouddriver/clouddriver-ecs/src/main/java/.../ecs/EcsCloudProvider.java`,
`.../ecs/EcsNativeCloudProvider.java`)

Atomic-operation **converters** are duplicated per provider via the annotation:

- classic converters are annotated `@EcsOperation(...)`
- native converters live in `.../deploy/converters/ecsnative/` and are annotated
  `@EcsNativeOperation(...)`, e.g. `EcsNativeCreateServerGroupAtomicOperationConverter`
  (`@EcsNativeOperation(AtomicOperations.CREATE_SERVER_GROUP)`, bean `ecsNativeCreateServerGroup`).

So a stage with `cloudProvider: "ecs-native"` is routed by clouddriver's operation-resolution to the
`@EcsNativeOperation`-annotated converter, fully isolated from the `@EcsOperation` converters. This
is why you can change ecs-native behavior without touching classic `ecs`.

**The native operations mostly subclass the classic ones** and override only what differs. Key one:
`EcsNativeCreateServerGroupAtomicOperation extends CreateServerGroupAtomicOperation`
(`.../deploy/ops/EcsNativeCreateServerGroupAtomicOperation.java`).

---

## 2. The create/deploy path (clouddriver)

`EcsNativeCreateServerGroupAtomicOperation.operate(...)` — **ecs-native is always in-place**. There
is no `inPlaceUpdate` flag; the operation decides create vs. update purely from whether the durable
service already exists:

1. It computes the **fixed** service name (`buildEcsServerGroupName(...).getServiceName()` →
   `app-stack-detail`, no `-vNNN`) and asks ECS via `DescribeServices` for that service, including
   `TAGS`. Only an ACTIVE/DRAINING service carrying `spinnaker:provider=ecs-native` is adopted for
   in-place update; an untagged fixed-name service is treated as external and causes the native
   write to fail rather than being overwritten.
2. If it exists, it calls `updateExistingServiceInPlace(...)`: registers a new task-def revision under
   the existing service's family and calls ECS **`UpdateService`** with `forceNewDeployment(true)` +
   the native `DeploymentConfiguration`. A validated native redeploy reapplies desired count,
   network configuration, service registries, placement constraints/strategy, capacity-provider
   strategy, platform version, health-check grace period, ECS Exec, and load-balancer mappings.
   Application Auto Scaling remains the owner of min/max capacity; ecs-native re-registers its
   scalable target and applies the pipeline's desired count on every authoritative redeploy. ECS
   does not support changing `launchType` through `UpdateService`, so launch type changes require
   service replacement rather than being silently reported as applied.
3. No new server group is created. Only the very first-ever deploy (service absent) falls through
   to `super.operate(...)` (the classic create path, `CreateService`) — but with two native overrides
   (below).

> Detection is by the computed fixed name via `DescribeServices`, **not** by a deploy-stage `source`
> block. That's what makes every ecs-native redeploy an in-place `UpdateService` rather than a
> colliding `CreateService`, without the caller having to opt in.

### Override A — fixed name (no `-vNNN`)

`buildEcsServerGroupName(...)` is overridden to return a fixed-name `EcsServerGroupName`:

- `EcsServerGroupName` (`.../ecs/names/EcsServerGroupName.java`) has a `fixedName` flag.
  - `getServiceName()` returns the bare family name (`app-stack-detail`) when `fixedName`, else
    `familyName + "-vNNN"`.
  - `getContainerName()` returns the family name when `fixedName` **or when the moniker sequence is
    null** (guards against `String.format("v%03d", null)` NPE).
- The native override builds the moniker with **no sequence** and passes `fixedName = true`.
  Classic `ecs` uses the default constructor → still versioned. `EcsServerGroupNameResolver`
  (the `-vNNN` next-sequence resolver) is **only** used by the classic path.

> Note: `getContainerName()` historically also fed the ECS *container definition* name and some LB /
> service-registry fallbacks in the shared `CreateServerGroupAtomicOperation`. For **artifact-based**
> deploys (task-definition artifact), the real container names come from the artifact, so the
> family-name fallback value is not used as an AWS container name in practice. If you move to
> non-artifact ecs-native deploys, re-check this.

### Override B — native `DeploymentConfiguration`

`makeServiceRequest(...)` (create) and `buildDeploymentConfiguration()` (in-place update) build the
AWS SDK `DeploymentConfiguration`:

- `strategy` — `ROLLING` (default) or `BLUE_GREEN`
- `minimumHealthyPercent` / `maximumPercent`
- `deploymentCircuitBreaker` (enable + rollback)
- `alarms` (deployment alarms)
- `bakeTimeInMinutes` — applied **whenever non-null, regardless of strategy** (see gotcha #3)
- ALB traffic-shift (`AdvancedConfiguration`: alternateTargetGroupArn / production+test listener
  rules / role) — this block **is** blue/green-only and is all-or-nothing. It is **optional only
  for a service with no load balancer**; once a target group is attached, AWS ECS **requires** it
  on every load balancer for `BLUE_GREEN` and rejects the deploy without it (a 400:
  `"advancedConfiguration field is required for all loadBalancers when using the Blue/green
  deployment strategy"`). clouddriver validates this up front and attaches the config on both the
  create and in-place update paths (see gotcha #7) rather than letting the request fail AWS-side.

Descriptions: `EcsNativeCreateServerGroupDescription` and `EcsNativeUpdateServiceDescription`
(`.../deploy/description/`). They extend the classic descriptions and add only the native knobs.

---

## 3. The read / caching model (clouddriver) — shared, and why that matters

**ecs-native shares the classic `ecs` caching agents and cache.** There are no separate native
caching agents. Consequences:

- Running ECS resources are cached once; both providers see the same services/tasks.
- `EcsServerClusterProvider` (`.../provider/view/EcsServerClusterProvider.java`) answers to the `ecs`
  provider and builds server groups from **all tasks in `service:<name>`** (old + new revision).
- `EcsNativeServerClusterProvider` (`.../provider/view/EcsNativeServerClusterProvider.java`) exists so
  that `cloudProvider: "ecs-native"`-scoped, exact lookups (getCluster / getServerGroup /
  getClusters(app, account)) resolve — it **delegates** those to the `ecs` provider. Its
  application-wide aggregate listings (`getClusters()`, `getClusterSummaries`, `getClusterDetails`)
  deliberately **return empty**, otherwise every ECS service would appear twice in Deck's Clusters
  view (once as `ecs`, once as `ecs-native`). The `ecs` provider is the single source of truth for
  unfiltered listings.

**Moniker / naming for a fixed-name service** (verified with Frigga 0.28.0):

- `Names.parseName("app-stack-detail")` → app=`app`, stack=`stack`, detail=`detail`,
  cluster=`app-stack-detail`, **sequence=null**.
- `Names.parseName("app-stack-detail-v000")` → same, but sequence=`0`.
- Name parsing still groups fixed and versioned services into the same Spinnaker cluster, but it is
  **not ownership evidence**. `EcsServerClusterProvider` sets `isNative` only when the cached ECS
  service carries `spinnaker:provider=ecs-native`; missing marker means classic/external.
- Native creation requires ECS service tagging, which depends on both account settings
  `serviceLongArnFormat` and `taskLongArnFormat`. If either is disabled, first native creation
  fails clearly rather than creating an unowned service that later redeploys could mistake for ours.

**Instance health** (`EcsTask.calculateHealthState`, `ContainerInformationService.toPlatformHealthState`):

- A task is `Up` as soon as `lastStatus == RUNNING` (unless the task def has a container health check
  still `UNKNOWN` → `Starting`, or `UNHEALTHY` → `Down`).
- ALB target-group health only refines this **when** `EcsTargetHealth` is cached for the task's
  target. `TaskHealthCachingAgent` sets LB health `Up` only when the ALB target is `healthy`, else
  `Unknown` (never `Down`/`Starting`).
- **This means a task can count as healthy before it is registered/healthy in the ALB.** This is the
  root of gotcha #4.

---

## 4. The orca deploy path

`CreateServerGroupStage.basicTasks(...)`
(`orca/orca-clouddriver/src/main/groovy/.../pipeline/servergroup/CreateServerGroupStage.groovy`)
builds the provider-agnostic task graph:

```
createServerGroup -> monitorDeploy -> [forceCacheRefresh] -> [tagServerGroup]
   -> waitForUpInstances -> [forceCacheRefresh]
   -> waitForEcsNativeServiceDeployment          # ONLY when cloudProvider == "ecs-native"
```

- `waitForUpInstances` (`WaitForUpInstancesTask`) completes on `healthyCount >= targetDesiredSize`,
  where `targetDesiredSize` = ECS service `desiredCount` and health is the instance health above.
- `waitForEcsNativeServiceDeployment` (`WaitForEcsNativeServiceDeploymentTask`,
  `.../tasks/providers/ecs/`) polls a clouddriver endpoint backed by ECS
  `ListServiceDeployments` / `DescribeServiceDeployments`. The preceding native operation publishes
  the expected task-definition ARN in `ecsNativeExpectedTaskDefinition`; the endpoint selects the
  matching stable `serviceDeploymentArn`, so a rollback deployment cannot satisfy the wait for the
  failed target. `SUCCESSFUL` → success; `ROLLBACK_SUCCESSFUL`, `ROLLBACK_FAILED`, `STOPPED`, and
  ordinary failure → terminal; in-progress and unknown future states keep polling. It resolves
  account/region/serverGroupName from stage context or the deploy stage's `deploy.server.groups`
  output.
  - Endpoint: `EcsNativeServiceDeploymentController`
    (`GET /ecs-native/serverGroups/{account}/{region}/{serverGroupName}/deploymentStatus?expectedTaskDefinition=...`),
    returns `EcsServiceDeploymentStatus`.

The orca server-group creator is `EcsNativeServerGroupCreator extends EcsServerGroupCreator`
(`.../tasks/providers/ecs/`), overriding only `getCloudProvider()` → `"ecs-native"`.

---

## 5. Gotchas that have already bitten us

1. **Groovy subclass dispatch (fixed).** `EcsServerGroupCreator` (orca, Groovy) calls inherited
   members inside **closures / `this.&` method pointers** (`fetchAndParseArtifact`,
   `coerceArtifactToList`, `getImageAddressFromDescription` inside `getContainerToImageMap`'s
   `.each{}`). Groovy resolves those via the MOP against the **runtime** class, so from the
   `EcsNativeServerGroupCreator` subclass they threw `MissingPropertyException` /
   `MissingMethodException` (`No such property: oortService`, etc.). Classic `ecs` worked because it
   ran as the base class. Fix: the fragile closure/method-pointer call sites were rewritten as plain
   `for` loops so they bind statically. **If you add new logic to `EcsServerGroupCreator`, avoid
   referencing instance members from inside closures/method-pointers**, or it will only fail for the
   subclass.

2. **kork Spring Boot 4 empty-string config (fixed in kork).** Not ecs-specific, but it crashed
   ecs-native clouddriver/orca startup: Boot 4 surfaces `spinnaker.extensibility.repositories: {}`
   as an empty **string**, which the kork `SpringEnvironmentConfigResolver` expanded into a `{"":""}`
   entry Jackson 3 refused to coerce. Fixed in `SpringEnvironmentConfigResolver` (drop blank values).

3. **Bake time is NOT blue/green-only in AWS.** AWS ECS supports `bakeTimeInMinutes` for the ECS
   deployment controller regardless of `ROLLING` vs `BLUE_GREEN` (only the `EXTERNAL` and
   `CODE_DEPLOY` controllers are excluded; rolling just has no default bake). Deck used to hide the
   bake-time field unless blue/green — that was a Deck-only restriction, since removed. clouddriver/
   orca already applied it unconditionally.

4. **`waitForUpInstances` completes early for in-place rolling updates.** Because the single durable
   service keeps the **old** revision's task `RUNNING` (satisfying `desiredCount`) while the new
   revision rolls out, and a task counts `Up` on `RUNNING` before ALB registration,
   `waitForUpInstances` can report SUCCEEDED while ECS `rolloutState` is still `IN_PROGRESS` and the
   new task isn't serving yet. That's why `waitForEcsNativeServiceDeployment` (rolloutState gate) was
   wired into the ecs-native path. If a deploy "succeeds" but the new revision isn't actually live,
   check whether the rolloutState task is present/working.

5. **Two distinct "strategy" axes — don't conflate them.** ecs-native has two independent knobs:
   - The **Spinnaker deployment strategy** (the stage's `strategy`: `None` / `redblack` /
     `rollingredblack` / `highlander`) must be **`None`** for ecs-native. Deck locks the picker to
     `None` (`DeploymentStrategyRegistry.registerProvider('ecs-native', [])`), and orca's
     `CreateServerGroupStage.basicTasks` throws `IllegalStateException` for a hand-edited pipeline
     with any non-`None` strategy. A Spinnaker red/black strategy would try to stand up a new
     versioned server group and disable/destroy the old one, which either collides with the fixed
     service name or runs as inert no-ops.
   - The **ECS deployment strategy** (`deploymentStrategy` in the native description: `ROLLING` /
     `BLUE_GREEN`) is what actually drives the rollout, applied to the service's native
     `DeploymentConfiguration`. This is the only rollout strategy that matters for ecs-native.

6. **Always in-place; the old versioned service is not auto-adopted.** Detection is by the computed
   fixed name (`DescribeServices`), not by a deploy-stage source. If you see both a `-vNNN` and a
   fixed-name service, an earlier deploy created the fixed one without removing the old versioned one
   — you may need to delete the stale one manually (ECS can't rename a service).

7. **Blue/green on a load-balanced service requires the ALB traffic-shift config.** AWS ECS requires
   an `advancedConfiguration` block on **every** load balancer when
   `deploymentConfiguration.strategy == BLUE_GREEN`, and rejects the deploy otherwise with a 400:
   `"advancedConfiguration field is required for all loadBalancers when using the Blue/green
   deployment strategy"`. The four ALB fields (`alternateTargetGroupArn`, `productionListenerRule`,
   `testListenerRule`, `blueGreenRoleArn`) are therefore optional **only** for a service with no
   load balancer — despite Deck presenting them as "optional". `BLUE_GREEN` + a target group without
   all four is a misconfiguration, not a valid "no swap" deploy.
   `EcsNativeCreateServerGroupAtomicOperation` validates this up front on **both** paths — the
   create path (`validateBlueGreenLoadBalancerConfig`, against the resolved `LoadBalancer` list) and
   the in-place `UpdateService` path (`validateBlueGreenInPlaceConfig`, against the description's
   declared `targetGroup` / `targetGroupMappings`) — so the deploy fails with an actionable message
   instead of an opaque AWS-side 400. Both paths also **attach** the `advancedConfiguration`: the
   create path via `makeServiceRequest`, and the in-place path by re-sending the resolved
   `loadBalancers` with the config on the `UpdateServiceRequest` (scoped to the blue/green case only,
   so a plain rolling in-place update leaves the service's load-balancer wiring untouched).

---

## 6. Debugging playbook

- **Find the pipeline execution / stage failure** (no Deck token needed): exec into the orca pod and
  hit its internal API:
  `kubectl -n <ns> exec <orca-pod> -c orca -- sh -c 'curl -s http://localhost:8083/pipelines/<executionId>'`
  then inspect the `createServerGroup` stage's `tasks[]` and `context.exception.details`.
- **Ground-truth the ECS side** (needs AWS creds for the account): `aws ecs describe-services
  --cluster <cluster> --services <name>` → check `deploymentController.type` (should be `ECS`),
  `deploymentConfiguration.strategy` / `bakeTimeInMinutes` / `deploymentCircuitBreaker`, and the
  PRIMARY deployment `rolloutState`. `aws ecs describe-tasks` for `lastStatus`/`healthStatus`.
- **rolloutState IN_PROGRESS but Spinnaker green?** That's gotcha #4 — confirm
  `waitForEcsNativeServiceDeployment` is in the task graph for the execution.
- **`MissingProperty`/`MissingMethod` in `EcsServerGroupCreator`?** Gotcha #1 — a closure/method
  pointer referencing an inherited member from the subclass.
- **Duplicate clusters in Deck's Clusters view?** Check `EcsNativeServerClusterProvider` aggregate
  listings return empty (gotcha in §3).

## 7. Build / deploy notes (staging)

- clouddriver and orca are separate images; ecs-native code spans both (naming/ops in clouddriver,
  creator + wait wiring in orca) and **deck** (the native deploy UI lives in `deck/packages/ecs`,
  toggled by `cloudProvider === 'ecs-native'`).
- After a code change, force a **clean** rebuild before packaging — the Gradle/Groovy incremental
  build has served **stale** jars/classes here (a `BUILD SUCCESSFUL` that didn't actually recompile).
  Verify the built artifact timestamp/digest changed.
- For the Docker image, **don't** use `--no-cache` just to bust the app-COPY layer — it forces
  re-uploading the ~1 GB base/tooling layers. A normal build reuses cached base layers and only
  uploads the changed app layer; the COPY layer is naturally fresh when the jar/webpack output
  changed. (Relevant on slow uplinks.)
