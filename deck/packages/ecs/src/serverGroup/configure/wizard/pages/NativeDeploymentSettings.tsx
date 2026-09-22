import React from 'react';

import { HelpField } from '@spinnaker/core';

import type { IEcsWizardPageProps } from './common';
import type { IEcsServerGroupCommand } from '../../serverGroupConfiguration.service';

const ECS_NATIVE = 'ecs-native';

export const isEcsNative = (command: IEcsServerGroupCommand): boolean => command.cloudProvider === ECS_NATIVE;

export const NativeDeploymentSettings = ({ command, onFieldChange }: IEcsWizardPageProps) => {
  const useNative = isEcsNative(command);
  const alarms = command.backingData?.filtered?.metricAlarms || [];
  const alarmNames: string[] = command.alarmNames || [];
  const strategy = command.deploymentStrategy || 'ROLLING';
  const isBlueGreen = strategy === 'BLUE_GREEN';

  const toggleAlarm = (alarmName: string, checked: boolean) => {
    const next = checked ? [...alarmNames, alarmName] : alarmNames.filter((name) => name !== alarmName);
    onFieldChange('alarmNames', next);
  };

  return (
    <div className="container-fluid form-horizontal" data-test-id="EcsServerGroupWizard.nativeDeployment">
      <div className="form-group">
        <div className="col-md-5 sm-label-right">
          Use native ECS deployment <HelpField id="ecs.native.useEcsNative" />
        </div>
        <div className="col-md-3">
          <input
            aria-label="Use native ECS deployment"
            checked={useNative}
            data-test-id="NativeDeployment.useEcsNative"
            onChange={(event) => onFieldChange('cloudProvider', event.target.checked ? ECS_NATIVE : 'ecs')}
            type="checkbox"
          />
        </div>
      </div>

      {!useNative && (
        <div style={{ color: '#666' }}>
          <p>
            <em>
              When enabled, this deploy rolls out through ECS&apos;s own native deployment lifecycle (durable service,
              deployment alarms, and blue/green) instead of Spinnaker&apos;s red/black orchestration. The account,
              cluster, task definition, and networking configured elsewhere in this wizard are unchanged.
            </em>
          </p>
        </div>
      )}

      {useNative && (
        <>
          <div className="form-group">
            <div className="col-md-5 sm-label-right">
              Circuit breaker rollback <HelpField id="ecs.native.deploymentCircuitBreakerRollback" />
            </div>
            <div className="col-md-3">
              <input
                aria-label="Automatically roll back a failed deployment"
                checked={!!command.deploymentCircuitBreakerRollback}
                data-test-id="NativeDeployment.deploymentCircuitBreakerRollback"
                disabled={!command.enableDeploymentCircuitBreaker}
                onChange={(event) => onFieldChange('deploymentCircuitBreakerRollback', event.target.checked)}
                type="checkbox"
              />
            </div>
            <div className="col-md-12">
              <span className="help-block" style={{ marginLeft: '0' }}>
                Requires <b>Enable Deployment Circuit Breaker</b> (Advanced Settings). When both are set, ECS
                automatically rolls back a failed deployment to the last completed one.
              </span>
            </div>
          </div>

          <div className="form-group">
            <div className="col-md-5 sm-label-right">
              <b>Deployment Alarms</b> <HelpField id="ecs.native.alarmNames" />
            </div>
            <div className="col-md-7">
              {alarms.length ? (
                alarms.map((alarm: any) => (
                  <div className="checkbox" key={alarm.alarmName}>
                    <label>
                      <input
                        checked={alarmNames.includes(alarm.alarmName)}
                        onChange={(event) => toggleAlarm(alarm.alarmName, event.target.checked)}
                        type="checkbox"
                      />{' '}
                      {alarm.alarmName}
                    </label>
                  </div>
                ))
              ) : (
                <span className="help-block">No account was selected, or no CloudWatch alarms are available.</span>
              )}
            </div>
          </div>

          {alarmNames.length > 0 && (
            <div className="form-group">
              <div className="col-md-5 sm-label-right">
                Roll back on alarm <HelpField id="ecs.native.deploymentAlarmsRollback" />
              </div>
              <div className="col-md-3">
                <input
                  aria-label="Automatically roll back a deployment that trips a named alarm"
                  checked={!!command.deploymentAlarmsRollback}
                  data-test-id="NativeDeployment.deploymentAlarmsRollback"
                  onChange={(event) => onFieldChange('deploymentAlarmsRollback', event.target.checked)}
                  type="checkbox"
                />
              </div>
            </div>
          )}

          <div className="form-group">
            <div className="col-md-5 sm-label-right">
              <b>Deployment Strategy</b> <HelpField id="ecs.native.deploymentStrategy" />
            </div>
            <div className="col-md-7">
              <select
                aria-label="Native ECS deployment strategy"
                className="form-control input-sm"
                data-test-id="NativeDeployment.deploymentStrategy"
                onChange={(event) => onFieldChange('deploymentStrategy', event.target.value)}
                value={strategy}
              >
                <option value="ROLLING">Rolling</option>
                <option value="BLUE_GREEN">Blue/Green</option>
              </select>
            </div>
          </div>

          {/* Bake time applies to both ROLLING and BLUE_GREEN. AWS ECS supports bakeTimeInMinutes for
              the ECS deployment controller regardless of strategy (it only excludes the EXTERNAL and
              CODE_DEPLOY controllers), so it is intentionally rendered outside the isBlueGreen block. */}
          <div className="form-group">
            <div className="col-md-5 sm-label-right">
              Bake Time (minutes) <HelpField id="ecs.native.bakeTimeInMinutes" />
            </div>
            <div className="col-md-2">
              <input
                aria-label="Bake time in minutes"
                className="form-control input-sm no-spel"
                data-test-id="NativeDeployment.bakeTimeInMinutes"
                onChange={(event) =>
                  onFieldChange('bakeTimeInMinutes', event.target.value === '' ? null : Number(event.target.value))
                }
                type="number"
                value={command.bakeTimeInMinutes ?? ''}
              />
            </div>
            <div className="col-md-12">
              <span className="help-block" style={{ marginLeft: '0' }}>
                Minutes ECS waits before terminating the previous service revision and marking the deployment complete.
                For Blue/Green this is the soak after the new task set reaches steady state; for Rolling it is the soak
                after the new tasks are healthy, before the old revision is fully retired.
              </span>
            </div>
          </div>

          {isBlueGreen && (
            <>
              <div className="form-group">
                <div className="sm-label-left">
                  <b>ALB traffic shift (optional)</b> <HelpField id="ecs.native.blueGreenAdvanced" />
                </div>
                <div className="col-md-12">
                  <span className="help-block" style={{ marginLeft: '0' }}>
                    Blue/Green works without these: ECS still stands up a new task set and swaps it into the existing
                    target group. Set all four only to route a separate ALB listener rule at the new task set for test
                    traffic before shifting production traffic over.
                  </span>
                </div>
              </div>

              <div className="form-group">
                <div className="col-md-5 sm-label-right">Alternate target group ARN</div>
                <div className="col-md-7">
                  <input
                    aria-label="Alternate target group ARN"
                    className="form-control input-sm no-spel"
                    data-test-id="NativeDeployment.alternateTargetGroupArn"
                    onChange={(event) => onFieldChange('alternateTargetGroupArn', event.target.value)}
                    type="text"
                    value={command.alternateTargetGroupArn || ''}
                  />
                </div>
              </div>

              <div className="form-group">
                <div className="col-md-5 sm-label-right">Production listener rule ARN</div>
                <div className="col-md-7">
                  <input
                    aria-label="Production listener rule ARN"
                    className="form-control input-sm no-spel"
                    data-test-id="NativeDeployment.productionListenerRule"
                    onChange={(event) => onFieldChange('productionListenerRule', event.target.value)}
                    type="text"
                    value={command.productionListenerRule || ''}
                  />
                </div>
              </div>

              <div className="form-group">
                <div className="col-md-5 sm-label-right">Test listener rule ARN</div>
                <div className="col-md-7">
                  <input
                    aria-label="Test listener rule ARN"
                    className="form-control input-sm no-spel"
                    data-test-id="NativeDeployment.testListenerRule"
                    onChange={(event) => onFieldChange('testListenerRule', event.target.value)}
                    type="text"
                    value={command.testListenerRule || ''}
                  />
                </div>
              </div>

              <div className="form-group">
                <div className="col-md-5 sm-label-right">Blue/Green IAM role ARN</div>
                <div className="col-md-7">
                  <input
                    aria-label="Blue/Green IAM role ARN"
                    className="form-control input-sm no-spel"
                    data-test-id="NativeDeployment.blueGreenRoleArn"
                    onChange={(event) => onFieldChange('blueGreenRoleArn', event.target.value)}
                    type="text"
                    value={command.blueGreenRoleArn || ''}
                  />
                </div>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
};
