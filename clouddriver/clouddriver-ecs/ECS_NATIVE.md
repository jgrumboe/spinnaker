# `ecs-native` provider behavior

This is the implementation guide for the opt-in ECS-native provider. It supersedes older RFC text
that described SDK v1, missing alarms, or a future-only blue/green implementation.

## Identity and ownership

`ecs-native` is a Deck/stage provider identity, selected by `cloudProvider: "ecs-native"` on a
stage. It shares `ecs` credentials, cache agents, and aggregate resource listings. Backend
server-group payloads remain stamped `cloudProvider: "ecs"` and use `isNative` to mark durable
native ownership. Exact provider-scoped server group, load balancer, subnet, security-group, role,
and instance reads use native delegates, while aggregate listings stay under `ecs` to avoid
duplicate clusters in Deck.

Native services use one fixed, unversioned ECS service name per cluster/family. Ownership is not
inferred from the Frigga name: the service must carry `spinnaker:provider=ecs-native`. On the first
create, the operation writes that tag. On later deploys it describes the fixed service with tags
and updates it only when the tag is present and the service is active or draining. An untagged
fixed-name service is external, so the operation fails closed instead of adopting it. The tagging
path requires long ECS service and task-definition ARN settings. These ownership and AWS-permission
checks need runtime verification in an ECS environment; unit tests do not establish them.

## Deploy and rollout

The first native deploy creates the durable service. Subsequent deploys register a new task
definition revision and call `UpdateService` in place with the native deployment configuration.
Application Auto Scaling remains the owner of min/max capacity. An authoritative redeploy
re-registers the scalable target when capacity is supplied and applies the desired count; it does
not silently change the autoscaling min/max policy.

The AWS SDK v2 request model supports configurable minimum/maximum healthy percentages, deployment
circuit-breaker rollback, deployment alarms, bake time, and `ROLLING`/`BLUE_GREEN` strategy fields.
Blue/green ALB traffic-shift configuration is validated and included when supplied. Native blue/green
pipelines use explicit Continue and Stop stages: those stages reference the preceding deploy stage,
resolve its exact service-deployment ARN, and never infer identity from the service name. The deploy
waits for `SUCCESSFUL` directly unless the opt-in `lifecyclePauseStage` is set. That adds an ECS PAUSE
lifecycle hook (with explicit `lifecyclePauseTimeoutMinutes` and a `ROLLBACK`-by-default timeout action)
and the deploy stage succeeds once the hook is `AWAITING_ACTION`. A downstream Continue stage then sends
the hook id and `CONTINUE` or `ROLLBACK` (a requested rollback ends the stage successfully). If no
Continue stage exists, ECS applies the timeout action on its own. Stop sends the exact Stop request
(always stop type `ROLLBACK`; verified on real ECS that `ABORT` is rejected) and waits for the rollback to finish.

Orca waits for the exact service-deployment ARN returned in the ECS create/update
`DeploymentResult` metadata. That ARN is the rollout identity consumed by Orca; it is not inferred
from the service name or task-definition ARN. `SUCCESSFUL` completes the wait; rollback, stopped,
and ordinary failure states terminate it. The preceding `waitForUpInstances` task is not
sufficient for an in-place rollout because old-revision tasks can remain running while the new
revision is still in progress.

## Lifecycle action semantics

Native lifecycle converters currently reuse the shared ECS service operations. Their effects on a
durable service are explicit:

- **Disable** suspends scheduled scaling, dynamic scale-in, and dynamic scale-out on the
  Application Auto Scaling target, then sets ECS desired count to `0`. It does not delete the
  service, task definitions, policies, or scalable target.
- **Enable** reads the target's max capacity, sets desired count to that value (or `1` if no target
  exists), and resumes scheduled scaling plus dynamic scale-in/out. It does not restore a prior
  desired count when that differs from max capacity.
- **Destroy** removes metric alarms and the scalable targets found through their scaling-policy
  action resources, scales the service to `0`, deletes the ECS service, and deregisters its current
  task definition. The operation does not promise that every historical task-definition revision
  or every scalable-target/policy object is removed; audit those separately.
- **Start** is still a stub and has no runtime effect.
- **Resize** and scaling-policy operations remain separate from native rollout. Native scaling
  policy upsert/delete converters fail closed because their inputs cannot prove ownership of the
  durable service before a write; they must not be interpreted as changing the service's ownership
  or deployment identity.
- **Terminate instances/tasks** is unsupported for native resources and fails closed because task
  IDs alone cannot prove that the tasks belong to an owned native service. Native operations do not
  issue an unscoped stop request.

The Spinnaker deployment strategy must be `None`. Native ECS `ROLLING`/`BLUE_GREEN` is a different
axis and controls ECS rollout. A classic Spinnaker red/black strategy would conflict with the
single durable service model.

## Clone and other fail-closed behavior

Native clone is intentionally not supported. Cloning cannot currently produce a safe durable
service identity and ownership decision, so Deck exposes the limitation and the native converter
rejects the operation rather than silently using classic versioned-service behavior. Native `BLUE_GREEN` lifecycle controls are available through explicit Continue/Stop stages. Deck
stores a reference to the preceding native deploy stage; the opaque ARN field remains only as a
backward-compatible API fallback. Orca resolves the ARN from that stage's outputs/context, pins
all lifecycle requests to it, and uses a PAUSE hook in `AWAITING_ACTION` as the explicit ECS lifecycle
gate before Continue. Stop never runs automatically on deployment failure.

## Verification boundary

The repository's unit tests verify operation routing, request construction, ownership checks, and
status mapping where covered. They do not verify live AWS behavior such as tag permissions,
Application Auto Scaling suspension/resumption, alarm-triggered rollback, blue/green traffic
shifting, or deletion cleanup. Those checks are deferred runtime verification and must be labeled
as such in release or deployment notes.
