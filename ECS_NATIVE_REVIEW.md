# ecs-native review findings (actionable)

Review of the `ecs-native` branch: commits `7d5af3ccfff07c557d0f3293a348303e62efca43..HEAD`
(49 commits, ~7.5k lines across clouddriver, orca, deck).

This document is a work list. Each finding has: evidence (file + line), why it matters, and a
suggested fix. Severity follows `CODE_STYLE.md` ("Using this guide as a reviewer agent").

## Verification state at review time

| Command | Result |
| --- | --- |
| `./gradlew ":clouddriver:clouddriver-ecs:test"` | BUILD SUCCESSFUL — 458 tests, 0 failures |
| `./gradlew ":orca:orca-clouddriver:test"` | BUILD SUCCESSFUL |
| `pnpm test` (from `deck/`) | 3541 tests pass |

Not run: the MiniStack integration suite
(`EcsNativeCreateServerGroupAtomicOperationMiniStackSpec`), and nothing was exercised against real
AWS. Findings about blue/green and rollback runtime semantics are reasoned from the pinned SDK model
plus AWS behavior, not observed — they are marked as such.

Pinned SDK is AWS SDK v2 `2.55.1` (`kork/spinnaker-dependencies/spinnaker-dependencies.gradle:10`,
`versions.awsv2`). Verified present in `software.amazon.awssdk:ecs:2.55.1`: `DeploymentAlarms`,
`AdvancedConfiguration`, `DescribeServiceDeploymentsRequest`, `ListServiceDeploymentsRequest`,
`StopServiceDeploymentRequest`, `ContinueServiceDeploymentRequest`, `ServiceDeploymentStatus`. So
none of the below is blocked on an SDK bump.

## What is sound (do not "fix" these)

- Second `CloudProvider` bean + `@EcsNativeOperation` annotation dispatch is the correct isolation
  mechanism, and keeping the native converters standalone (not subclassing the `@EcsOperation`
  converters) is right — `getBeansWithAnnotation` walks superclasses and would leak a child into the
  `ecs` matches.
- Reusing the existing caching agents and adding id-exact read-path delegates
  (`EcsNativeServerClusterProvider`, `EcsNativeLoadBalancerProvider`, `EcsNativeSubnetProvider`,
  `EcsNativeSecurityGroupProvider`, `EcsNativeRoleProvider`, `EcsNativeInstanceProvider`) is
  correct. The bean-selection-by-exact-`getCloudProviderId()` trap is real.
- `EcsNativeServerClusterProvider` returning empty for the application-wide aggregate listings
  (`getClusters()`, `getClusterSummaries`, `getClusterDetails`) is the right call — delegating them
  would double every ECS service in the Clusters view.
- Fixed-name durable service + `UpdateService` + a rolloutState gate is the right overall shape.
- The `getContainerName()` fallback (family name when `fixedName` **or** when
  `moniker.getSequence() == null`) is load-bearing: the in-place path builds
  `new EcsServerGroupName(existingServiceName)` with `fixedName=false`, and the null-sequence branch
  is what keeps the container name consistent with the create path. Keep both conditions.
- The Groovy MOP workarounds in `EcsServerGroupCreator` (plain `for` loops instead of closures /
  `this.&method` pointers, `oortService` widened to `protected`) are a genuine fix, not cargo cult.

---

# Blocking

## B1. ecs-native operations get zero description validation

**Evidence**

- `AnnotationsBasedAtomicOperationsRegistry.getAtomicOperationDescriptionValidator`
  (`clouddriver/clouddriver-core/src/main/java/com/netflix/spinnaker/clouddriver/orchestration/AnnotationsBasedAtomicOperationsRegistry.java`)
  selects a `DescriptionValidator` bean that carries the provider's operation annotation.
- `grep -rl "EcsNativeOperation" clouddriver/clouddriver-ecs/src/main/java` returns only
  `EcsNativeCloudProvider`, `EcsNativeOperation`, and the 12 converters under
  `deploy/converters/ecsnative/`. No validator.
- `CompositeDescriptionValidator.validate` (clouddriver-core) with a null validator only logs
  `"No validator found for operation %s and cloud provider %s"` and returns.

**Impact** — every ecs-native operation bypasses the checks classic `ecs` enforces:
`validateCredentials`, `validateCapacity`, placement-strategy value whitelists
(`BINPACK_VALUES` / `SPREAD_VALUES`), reserved environment variables
(`SERVER_GROUP`, `CLOUD_STACK`, `CLOUD_DETAIL`), and target-group / container-port pairing. See
`clouddriver/clouddriver-ecs/src/main/java/.../ecs/deploy/validators/EcsCreateServerGroupDescriptionValidator.java`.
Bad input reaches AWS and fails there, or succeeds with the wrong shape.

**Fix** — add `@EcsNativeOperation`-annotated validators mirroring the `@EcsOperation` set. Unlike
the converters, validators **can** subclass the existing ones (the superclass-walk leak only
matters when the same bean type is resolved for both providers via `getBeansWithAnnotation`; verify
this with a test before relying on it — if it does leak, make them standalone). Add the native
knobs to the create validator too: min/max percent bounds, `alarmNames` non-empty when
`enableDeploymentAlarms` (see S6), and the four blue/green ALB fields all-or-nothing (that check
currently lives in the operation, which is too late for a dry-run).

**Test** — per `CODE_STYLE.md` §13, drive validation through the real registry lookup, not by
asserting the annotation is present.

## B2. Every redeploy applies only `taskDefinition` + `deploymentConfiguration`

**Evidence** — `EcsNativeCreateServerGroupAtomicOperation.updateExistingServiceInPlace`
(`clouddriver/clouddriver-ecs/src/main/java/.../ecs/deploy/ops/EcsNativeCreateServerGroupAtomicOperation.java:151`)
builds an `UpdateServiceRequest` with only: `cluster`, `service`, `taskDefinition`,
`forceNewDeployment(true)`, `deploymentConfiguration`, and `loadBalancers` **only** when an
`AdvancedConfiguration` was built (blue/green).

The first-deploy path (`CreateServerGroupAtomicOperation.makeServiceRequest`, same module) also
sets: `desiredCount`, `networkConfiguration` (resolved subnets + security groups),
`serviceRegistries`, `placementConstraints`, `placementStrategy`,
`launchType` / `capacityProviderStrategy`, `platformVersion`,
`healthCheckGracePeriodSeconds`, `enableExecuteCommand`, `tags` / `propagateTags`. And
`CreateServerGroupAtomicOperation.operate` additionally calls `registerAutoScalingGroup(...)` and,
when requested, `ecsCloudMetricService.copyScalingPolicies(...)`.

Since ecs-native is **always** in-place after the first deploy, none of that ever runs again.

**Impact** — a user edits capacity, firewalls, subnets, service discovery, launch type, or grace
period in the deploy wizard; the stage reports success; ECS is unchanged. Silent configuration
drift with a green pipeline. This is the largest functional gap in the branch.

**Fix** — extend the in-place `UpdateServiceRequest` to carry the fields ECS `UpdateService`
supports: `desiredCount`, `networkConfiguration`, `serviceRegistries`, `placementConstraints`,
`placementStrategy`, `capacityProviderStrategy`, `platformVersion`,
`healthCheckGracePeriodSeconds`, `enableExecuteCommand`, `loadBalancers`. Also decide and document
the scalable-target story: either re-run `registerAutoScalingGroup` on redeploy, or explicitly
declare capacity owner-of-record to be autoscaling and remove/disable the capacity fields for
ecs-native in Deck. Do not leave it silently ignored.

Note the one deliberate exception worth keeping: blindly resetting `desiredCount` on every deploy
would stomp an autoscaled count. If that is the intent, make it a documented, explicit decision in
`ECS_NATIVE.md`, and reflect it in the wizard.

## B3. The rollout wait can report SUCCEEDED for a deploy ECS rolled back

**Evidence**

- `WaitForEcsNativeServiceDeploymentTask`
  (`orca/orca-clouddriver/src/main/java/.../tasks/providers/ecs/WaitForEcsNativeServiceDeploymentTask.java:103,108`)
  branches purely on `status.getRolloutState()` — `COMPLETED` → SUCCEEDED, `FAILED` → TERMINAL.
- `EcsNativeServiceDeploymentController`
  (`clouddriver/clouddriver-ecs/src/main/java/.../ecs/controllers/EcsNativeServiceDeploymentController.java`)
  returns whichever `Deployment` currently has `status == "PRIMARY"`.
- Nothing pins the deployment this stage created. `buildDeploymentResult` emits only
  region:serviceName.
- Backoff is 10s (`:56`).

**Impact (reasoned from ECS semantics, not observed)** — on circuit-breaker or deployment-alarm
rollback, ECS starts a *new* rollback deployment which becomes `PRIMARY` and reaches `COMPLETED`.
If polling misses the `FAILED` window on the original deployment, orca marks the stage SUCCEEDED
while the service is running the previous task definition. That is precisely the false-success the
task was introduced to prevent (`ECS_NATIVE.md` gotcha #4).

**Fix, option A (smaller)** — have the create/update op put the new deployment id (from
`ecs.updateService(...).service()` / `createService(...)`) and/or the registered task-definition ARN
into the operation result, surface it in the stage context, and have the wait task require the
observed `deploymentId` (or the deployment's `taskDefinition`) to match before honoring `COMPLETED`.
A `PRIMARY` deployment whose id differs from the expected one means a rollback happened → TERMINAL.

**Fix, option B (better, more work)** — switch the controller to
`ListServiceDeployments` / `DescribeServiceDeployments`. These return a stable
`serviceDeploymentArn` plus `ServiceDeploymentStatus` values including `ROLLBACK_IN_PROGRESS`,
`ROLLBACK_SUCCESSFUL`, `ROLLBACK_FAILED`, `STOPPED` — which is what the original RFC
(`clouddriver/clouddriver-ecs/docs/ecs-v2-modernization.md` §4.1) specified. Both APIs exist in the
pinned SDK. This also unblocks S9 (blue/green lifecycle).

**Test** — a spec where the first poll returns `IN_PROGRESS` for deployment `d-1` and the second
returns `COMPLETED` for deployment `d-2`; assert TERMINAL, not SUCCEEDED.

## B4. `isNative` misdetects every ECS service without a `-vNNN` suffix

**Evidence** — `EcsServerClusterProvider`
(`clouddriver/clouddriver-ecs/src/main/java/.../ecs/provider/view/EcsServerClusterProvider.java:457`):

```java
boolean isNative = moniker != null && moniker.getSequence() == null;
```

and line `:466` gates `attachTaskDefinitionRevisions(...)` on it.

**Impact** — the caching agents cache **every** ECS service in the account, not only
Spinnaker-created ones. Any service created by hand, Terraform, CDK, or CodeDeploy parses to a null
Frigga sequence and is therefore flagged native. Two consequences:

1. Deck routes the Rollback action to `EcsNativeRollbackServerGroupModal` for services ecs-native
   never deployed (`deck/packages/ecs/src/serverGroup/details/EcsServerGroupActions.tsx`), offering
   an in-place `UpdateService` against a service Spinnaker does not own.
2. Every details-pane open on such a service fires 1 `ListTaskDefinitions` + up to 50
   `DescribeTaskDefinition` live AWS calls (see S10).

The commit history shows three attempts at this signal (cloudProvider → rollout fields → "explicit
`isNative` flag"), but the flag is still derived from name shape.

**Fix** — use a durable marker written by the ecs-native create path. Options, best first:

1. An ECS service tag (e.g. `spinnaker:provider=ecs-native`) written in
   `makeServiceRequest`/`createService` and read back by the caching agent into
   `cache/model/Service`. Note the existing constraint documented in `ECS_NATIVE.md` §3: tagging
   requires `serviceLongArnFormat`/`taskLongArnFormat` on the account, and
   `makeServiceRequest` already throws if tags are set without it — so needs a fallback.
2. A Spinnaker-side record keyed by cluster/service.
3. As a stopgap, narrow the heuristic: require null sequence **and** that the service name equals
   the Frigga cluster name **and** the family matches, and treat a missing signal as non-native.

Whatever is chosen, the read path must not fall back to "any unversioned ECS service is ours".

---

# Should fix

## S1. Deck does not lock the Spinnaker deployment strategy to `None`

**Evidence**

- `ECS_NATIVE.md` gotcha #5 and the comment in
  `orca/.../CreateServerGroupStage.groovy:104-107` both claim Deck locks the picker.
- `deck/packages/ecs/src/ecsNative.module.ts` does
  `DeploymentStrategyRegistry.registerProvider('ecs-native', [])`.
- But `deck/packages/core/src/deploymentStrategy/DeploymentStrategySelector.tsx:37` resolves
  `this.props.command.selectedProvider || this.props.command.cloudProvider`, and
  `deck/packages/ecs/src/serverGroup/configure/wizard/pages/BasicSettings.tsx:85` renders the
  selector gated on `command.selectedProvider` — which stays `'ecs'` for the ECS wizard.
- `deck/packages/ecs/src/ecs.module.ts:102` registers `'ecs'` with `['redblack']`.

**Impact** — the user can tick "Use native ECS deployment" and still pick Red/Black. The only
feedback is orca throwing `IllegalStateException` from `basicTasks` at stage planning
(`CreateServerGroupStage.groovy:109-114`).

**Fix** — in `validateEcsNativeDeployment`
(`deck/packages/ecs/src/serverGroup/configure/wizard/pages/validation.tsx`) add: when
`cloudProvider === 'ecs-native'` and `strategy` is set and not `'none'`/`''`, emit an error on
`strategy`. Additionally, clear `strategy` in the toggle's `onChange` in
`NativeDeploymentSettings.tsx`. Then correct `ECS_NATIVE.md` gotcha #5 and the orca comment, which
currently assert a lock that does not exist.

## S2. Partial `deploymentConfiguration` overwrites existing service settings

**Evidence** — `EcsNativeCreateServerGroupAtomicOperation.buildDeploymentConfiguration()`
(`:386-427`) and the near-identical
`EcsNativeUpdateServiceAtomicOperation.buildDeploymentConfiguration()`
(`clouddriver/clouddriver-ecs/src/main/java/.../ecs/deploy/ops/EcsNativeUpdateServiceAtomicOperation.java:84-120`).
Any single non-default field trips `hasConfig`, and the builder then **unconditionally** emits
`deploymentCircuitBreaker(enable=<flag>, rollback=<flag>)` while leaving unset `minimumHealthyPercent`
/ `maximumPercent` absent from the request.

**Impact** — `UpdateService.deploymentConfiguration` is a replacement, not a patch. Setting only
`bakeTimeInMinutes` on a redeploy disables a circuit breaker the service already had and resets the
rolling bounds to ECS defaults.

**Fix** — either read the service's current `deploymentConfiguration` (the in-place path already
calls `DescribeServices` in `resolveExistingServiceName()` — return the `Service`, not just the
name, and reuse it) and merge, or require the full intended config to be sent and document that it
is authoritative. Merging is the less surprising behavior.

## S3. Six of seven `HelpField` ids are never registered

**Evidence** — `deck/packages/ecs/src/ecs.help.ts` adds only `ecs.native.blueGreenAdvanced`.
`NativeDeploymentSettings.tsx` also references `ecs.native.useEcsNative`,
`ecs.native.deploymentCircuitBreakerRollback`, `ecs.native.alarmNames`,
`ecs.native.deploymentAlarmsRollback`, `ecs.native.deploymentStrategy`,
`ecs.native.bakeTimeInMinutes`.

**Impact** — six empty help popovers on the new wizard page.

**Fix** — register all six in `ecs.help.ts`.

## S4. `minimumHealthyPercent` / `maximumPercent` have no UI

**Evidence** — `grep -rn "minimumHealthyPercent" deck/packages/ecs/src deck/packages/core/src`
returns nothing. The fields exist on `EcsNativeCreateServerGroupDescription` and
`EcsNativeUpdateServiceDescription`.

**Impact** — the RFC's headline configurable
(`ecs-v2-modernization.md` §4.1) is reachable only by hand-editing pipeline JSON.

**Fix** — add both to `NativeDeploymentSettings.tsx` (number inputs, blank = unset), add them to
`IEcsServerGroupCommand` in `serverGroupConfiguration.service.ts`, and validate bounds
(1–100 / >=100, max >= min) in `validateEcsNativeDeployment` and in the new backend validator from
B1.

## S5. Provider-specific code added to `packages/core`

**Evidence**

- `deck/packages/core/src/serverGroup/pod/EcsRolloutStateBadge.tsx` (new file in core).
- `deck/packages/core/src/serverGroup/ServerGroupHeader.tsx` imports it directly into the shared
  `SequenceAndBuildAndImages`, reading `(serverGroup as any).taskDefinitionRevision`,
  `.rolloutState`, `.rolloutStateReason`.
- `deck/packages/core/src/cluster/rollups.less` gains `.ecs-rollout-state*` rules.

**Impact** — ECS concepts and `as any` casts leak into the provider-agnostic core component that
every provider renders. `CODE_STYLE.md` §11.

**Fix** — `ServerGroupHeader` is already `@Overridable`. Register an ECS override from
`deck/packages/ecs` instead, and move the badge + LESS into `packages/ecs`. If a shared hook is
genuinely needed, add a typed optional field to `IServerGroup` rather than casting.

## S6. `DeploymentAlarms` can be sent enabled with no alarm names

**Evidence** — `buildDeploymentAlarms` in both native ops returns
`DeploymentAlarms.builder().alarmNames(description.getAlarmNames()).enable(true)...` whenever
`enableDeploymentAlarms` is true, even with `alarmNames == null`.

**Impact** — AWS rejects `enable=true` with no alarm names; the deploy fails with an opaque 400.

**Fix** — reject at validation time (B1) and/or return `null` from `buildDeploymentAlarms` when
there are no names.

## S7. Duplicated logic that the same diff already had a pattern for

**Evidence**

- `buildDeploymentConfiguration()` and `buildDeploymentAlarms()` exist twice, near-identically, in
  `EcsNativeCreateServerGroupAtomicOperation` and `EcsNativeUpdateServiceAtomicOperation`.
- `validateBlueGreenLoadBalancerConfig` (`:264`) and `validateBlueGreenInPlaceConfig` (`:289`)
  duplicate an identical 5-line error message.
- `resolveTaskRoleArn` (`:447`) re-implements `CreateServerGroupAtomicOperation`'s private
  `inferAssumedRoleArn` (`:800`) — while the same diff widened `buildEcsServerGroupName` and
  `retrieveLoadBalancers` to `protected` for exactly this reason.

**Impact** — `CODE_STYLE.md` §1 and §11. Divergence risk: a fix applied to one copy silently misses
the other.

**Fix** — extract the deployment-config/alarms builders into a shared helper taking a small
interface (or a common base description), collapse the two blue/green validators into one taking a
"has load balancer" boolean, and widen `inferAssumedRoleArn` to `protected` instead of copying it.

## S8. Cloning an ecs-native server group is a silent no-op

**Evidence**

- `deck/packages/ecs/src/serverGroup/configure/serverGroupCommandBuilder.service.js` now preserves
  `cloudProvider: 'ecs-native'` when building from an existing native server group.
- `EcsCloneServerGroupModal.tsx:549` calls `serverGroupWriter.cloneServerGroup`, which sets
  `command.type = 'cloneServerGroup'` when `viewState.mode === 'clone'`
  (`deck/packages/core/src/serverGroup/serverGroupWriter.service.ts:32-37`).
- That routes to `EcsNativeCloneServiceAtomicOperationConverter`, which constructs the classic
  `CloneServiceAtomicOperation` — whose `operate()` body is `// TODO - implement this stub` and
  returns null.
- The strategy guard and the rolloutState wait are wired only into `CreateServerGroupStage`, not
  into the clone stage.

**Impact** — a native clone reports success and does nothing. The stub is pre-existing for classic
`ecs` too, but this branch added code that implies clone works for ecs-native.

**Fix** — pick one and make it explicit: (a) point the native `CLONE_SERVER_GROUP` converter at
`EcsNativeCreateServerGroupAtomicOperation` (clone → deploy into the durable service), or (b) leave
it unimplemented and fail fast with a clear message, plus a `TODO` per `CODE_STYLE.md` §10. Either
way, if clone is supported, mirror the `CreateServerGroupStage` ecs-native branch (strategy guard +
`waitForEcsNativeServiceDeployment`) into the clone stage.

## S9. Native blue/green is only half-wired

**Evidence** — the `DeploymentConfiguration.strategy`, `bakeTimeInMinutes`, and
`LoadBalancer.advancedConfiguration` fields are sent, but nothing in the branch calls
`StopServiceDeployment` (abort an in-flight deployment) or `ContinueServiceDeployment` (advance past
bake / the test-traffic gate). Both exist in `software.amazon.awssdk:ecs:2.55.1`. The wait task's
three-state read of the `PRIMARY` `Deployment` cannot represent blue/green lifecycle stages.

Also: `EcsNativeUpdateServiceDescription.bakeTimeInMinutes` javadoc says
"Ignored for `ROLLING`", which contradicts `EcsNativeCreateServerGroupDescription`'s javadoc **and**
`ECS_NATIVE.md` gotcha #3 (both say bake time applies to both strategies).

**Impact** — a blue/green ecs-native deploy can be started but not aborted or continued from
Spinnaker, and the stage cannot report where in the lifecycle it is. The RFC listed rollback as
`StopServiceDeployment`.

**Fix** — do B3 option B first (service-deployment APIs), then add abort/continue operations and
surface lifecycle stage in the wait task and the details pane. Fix the contradictory javadoc now.

## S10. Task-definition revision listing is up to 51 serial AWS calls per details open

**Evidence** — `EcsTaskDefinitionRevisionService.listRevisions`
(`clouddriver/clouddriver-ecs/src/main/java/.../ecs/services/EcsTaskDefinitionRevisionService.java`)
paginates `ListTaskDefinitions` and then calls `DescribeTaskDefinition` once per revision, up to
`MAX_REVISIONS = 50`. Invoked from `EcsServerClusterProvider.attachTaskDefinitionRevisions`
(`:493`) on the `includeDetails` path for every server group where `isNative` is true — which per
B4 includes services ecs-native never deployed.

**Impact** — latency and throttling risk on the server-group details endpoint, amplified by B4.
`CODE_STYLE.md` §7.

**Fix** — any of: fetch revisions lazily when the rollback modal opens rather than on every details
load; cache per family with a short TTL; drop the per-revision `DescribeTaskDefinition` and parse
`family:revision` out of the ARN (container images become a lazy detail); lower `MAX_REVISIONS`.
Fixing B4 first removes most of the volume.

## S11. Inconsistent account-name resolution between two new read paths

**Evidence** — `EcsNativeServiceDeploymentController.resolveCredentials` deliberately falls back to
a case-insensitive scan over `credentialsRepository.getAll()`, while
`EcsServerClusterProvider.attachTaskDefinitionRevisions` (`:495`) uses plain
`credentialsRepository.getOne(account)` and silently returns on null.

**Impact** — the rollback picker can come back empty for exactly the account-casing case the
controller was patched to handle.

**Fix** — extract one resolution helper and use it in both. Note that the controller's case-insensitive
scan is itself a `getAll()`-then-filter pattern (`CODE_STYLE.md` §7); if the casing mismatch has a
real upstream cause, fixing the caller is better than scanning.

---

# Nits and stale docs

## N1. `ECS_NATIVE.md` §4 task-graph order is wrong

The doc shows:

```
... -> waitForUpInstances -> [forceCacheRefresh] -> waitForEcsNativeServiceDeployment
```

The code (`CreateServerGroupStage.groovy:116-122`) inserts the wait **before** `waitForUpInstances`
and before the trailing `forceCacheRefresh`. The inline comment at `:95-98` describes the actual
order. Fix the doc.

## N2. `ecsNative.module.ts` javadoc asserts something untrue

It claims the resource's `cloudProviderId` "the backend's ecs-native read-path delegates already
report as `'ecs-native'`". They do not — `EcsServerClusterProvider` stamps `type`/`cloudProvider` as
`ecs` on every ECS server group, which is the stated reason `isNative` exists
(see the comment at `EcsServerClusterProvider:453-456` and in `EcsServerGroupActions.tsx`).

Consequence: the `CloudProviderRegistry.registerProvider('ecs-native', ...)` block is probably dead
code. Confirm whether any view resolves by `'ecs-native'`; if not, delete the registration (keep
`DeploymentStrategyRegistry.registerProvider('ecs-native', [])` only if S1's real fix still needs
it) and correct the comment.

## N3. `docs/ecs-v2-modernization.md` is stale

Phase 4 still says deployment alarms were "attempted, then reverted — unsupported by the pinned
SDK", native blue/green "not attempted", and the SDK v2 upgrade pending. All three are implemented
on this branch against SDK v2 `2.55.1`. Also the doc's §4.2 claim that views are served entirely by
the existing `ecs` providers predates the `isNative` / rollout-state additions. Rewrite or mark
superseded by `ECS_NATIVE.md`.

## N4. New backend tests are Groovy/Spock

`CODE_STYLE.md` §14 prefers Java (JUnit 5) for new backend tests. Not blocking — the surrounding
`clouddriver-ecs` suite is Groovy, and some new tests here already are Java
(`EcsServerGroupSerializationTest`, `ServiceCachingAgentTest`). Worth noting for any new specs added
while fixing the above.

## N5. Reuse of `disable` / `enable` / `destroy` for a durable service is unexamined

`EcsNativeDisableServiceAtomicOperationConverter`, `...Enable...`, `...Destroy...` all route to the
classic operations unchanged. For a single durable service, "disable" and "destroy" have different
semantics than for a throwaway versioned server group (destroy deletes the one service; the next
deploy recreates it from scratch, losing the scalable target). Deck still shows all three actions
for native server groups. At minimum document the intended semantics in `ECS_NATIVE.md`; consider
whether Deck should hide or re-label them when `isNative`.

---

# Suggested order of work

1. **B1** (validators) — cheap, and it is the safety net several other findings rely on (S4, S6).
2. **B4** (`isNative` marker) — unblocks S10 and stops the false-positive rollback surface.
3. **B2** (in-place field coverage) — biggest functional gap; needs a decision on capacity ownership.
4. **B3** (pin the deployment / move to service-deployment APIs) — then **S9** on top of it.
5. **S1, S2, S3, S4, S6** — user-facing correctness and UX, each small.
6. **S5, S7, S8, S10, S11** — structure and performance.
7. **N1–N5** — docs and comments; do these alongside whichever finding touches the same file so the
   docs never describe behavior that no longer exists.

Re-run after each group:

```bash
./gradlew ":clouddriver:clouddriver-ecs:test" ":orca:orca-clouddriver:test"
./gradlew spotlessCheck
cd deck && pnpm test && pnpm lint
```

Add a regression test per fix (`CODE_STYLE.md` §12). For B3 specifically, the test must exercise
the deployment-id-changed case, not just the happy path.
