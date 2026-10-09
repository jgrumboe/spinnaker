# ECS-native implementation notes

Status: implementation reference (supersedes the original RFC in this file)
Scope: clouddriver, orca, and Deck

## Summary

`ecs-native` is an opt-in provider identity for ECS deployments. It reuses the existing ECS
accounts, caching agents, and resource views, but routes native stages to an in-place service
operation. The service is the durable unit: the first deploy creates the fixed-name service and
later deploys register a task-definition revision and call ECS `UpdateService`.

This document describes the behavior currently implemented in the repository. Source inspection
is not a substitute for AWS integration verification; any behavior not covered by the existing
unit tests must be verified against a real or emulated ECS control plane before release.

## Provider and read-path behavior

- `ecs-native` is a separate clouddriver provider and operation annotation. Native converters are
  selected by the exact `cloudProvider` value and do not alter the classic `ecs` converters.
- There is no `ecs-native` credential type. Account resolution is by account name, and accounts
  remain typed `ecs` for Deck account discovery.
- The ECS cache is shared. Exact resource lookups use native provider-scoped delegates when the
  lookup requests the `ecs-native` identity, while returned server-group payloads remain stamped
  `cloudProvider: "ecs"` and mark durable native ownership with `isNative`; aggregate listings
  remain owned by `ecs` so resources do not appear twice.
- Deck registers a second provider configuration by reusing the `ecs` component references. It is
  needed for provider-scoped native lookups and views, not for a second account or cache.

## Durable service deployment

The native create operation computes the unversioned family/service name (for example,
`app-stack-detail`) and calls `DescribeServices` with tags. An existing service is eligible for an
in-place update only when it is active/draining and has the ownership tag
`spinnaker:provider=ecs-native`. A fixed-name service without that tag is treated as external and
causes the operation to fail closed; it is never adopted or overwritten. The first deploy creates
the service and applies the ownership tag. Long ECS service and task-definition ARNs are required
for that tagging path.

An in-place deploy registers a new revision under the existing family, calls `UpdateService` with a
native deployment configuration, and preserves the service's durable identity. Application Auto
Scaling owns the service's min/max capacity. When the deploy includes capacity, the operation
re-registers the scalable target and applies the requested desired count; it does not silently
replace Application Auto Scaling with ECS-only capacity settings.

The native deployment configuration supports rolling bounds, circuit-breaker enable/rollback,
deployment alarms, bake time, and the ECS `ROLLING` or `BLUE_GREEN` strategy. Blue/green traffic
shift fields are sent when configured and are validated before the AWS request. The end-to-end
lifecycle uses explicit Continue and Stop stages. Deck references the preceding native deploy stage
(with the ARN field retained only as an API fallback), and Orca resolves and pins the exact deployment
ARN from that stage's outputs/context. For `BLUE_GREEN`, an opt-in PAUSE lifecycle hook
(`lifecyclePauseStage`) makes the deploy wait succeed once the hook is `AWAITING_ACTION`; Continue then
resolves the hook id and issues the exact `ContinueServiceDeployment` request with `CONTINUE` or
`ROLLBACK` and waits for `SUCCESSFUL` (or the requested rollback). Stop issues the exact
`StopServiceDeployment` request (stop type `ROLLBACK`; `ABORT` is rejected by ECS) and polls the same ARN until the requested
rollback completes.
Stop is never added as an automatic deployment-failure action.

Create and update return the exact ECS service-deployment ARN in `DeploymentResult` metadata;
that ARN is the rollout identity consumed by Orca. Orca waits for that service deployment rather
than inferring identity from the service name or task-definition ARN. ECS success, rollback,
stopped, and failure states are not inferred from the older server-group instance heuristic. The
normal `waitForUpInstances` task can still complete early because old revision tasks remain
running; the native deployment wait is the authoritative rollout gate.

## Lifecycle actions and limitations

Native stages must use Spinnaker strategy `None`; ECS's deployment strategy owns the rollout.
The following operations currently route through the shared ECS service operations, with these
durable-service consequences:

| Action | Effect on the durable service | Application Auto Scaling effect |
| --- | --- | --- |
| Disable | Suspends scheduled, dynamic-in, and dynamic-out scaling, then updates ECS desired count to `0`. | The scalable target remains registered but all three scaling modes are suspended. |
| Enable | Reads the scalable target's max capacity, updates ECS desired count to that value (or `1` when no target exists), then resumes all three scaling modes. | The existing scalable target is resumed; its min/max settings are not replaced by this action. |
| Destroy | Deletes metric alarms and the scalable targets discoverable through those alarm actions, scales the service to `0`, deletes the ECS service, and deregisters its current task definition. | Do not treat Destroy as a reversible pause. Any remaining scalable-target/policy state not discovered through the metric-alarm cleanup path must be audited separately. |

Clone is deliberately unavailable for the durable native path. A clone description and stage graph
cannot currently produce a safe fixed-service ownership identity without risking an update to the
wrong durable service; the native converter/UI therefore reject or mark clone unsupported rather
than falling back to classic versioned-service semantics. Native `Start` is also not implemented
(the existing operation is a stub). `BLUE_GREEN` Continue and Stop are explicit pipeline stages,
not automatic failure handlers; their exact deployment identity is resolved from the preceding
deploy stage and lifecycle polling remains pinned to that ARN.

## SDK, alarms, and deferred verification

The implementation uses the AWS SDK v2 ECS and Application Auto Scaling clients. Deployment alarms
are native ECS deployment alarms, distinct from the existing ECS scaling-policy/CloudWatch alarm
copy and cleanup code. The repository tests cover request construction and operation routing, but
they do not prove AWS-side rollout, alarm-triggered rollback, traffic shifting, tag permissions,
or scalable-target behavior. Those remain explicitly deferred runtime verification items.
