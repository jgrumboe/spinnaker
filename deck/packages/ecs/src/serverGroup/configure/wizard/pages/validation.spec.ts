import { validateEcsNativeDeployment } from './validation';

describe('validateEcsNativeDeployment', () => {
  it('returns no errors when cloudProvider is plain ecs, regardless of blue/green fields', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs',
      alternateTargetGroupArn: 'arn:alternate-target-group',
    } as any);
    expect(errors).toEqual({});
  });

  it('returns no errors when none of the blue/green ALB fields are set', () => {
    const errors = validateEcsNativeDeployment({ cloudProvider: 'ecs-native' } as any);
    expect(errors).toEqual({});
  });

  it('returns no errors when all four blue/green ALB fields are set', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      alternateTargetGroupArn: 'arn:alternate-target-group',
      productionListenerRule: 'arn:production-rule',
      testListenerRule: 'arn:test-rule',
      blueGreenRoleArn: 'arn:aws:iam::123456789012:role/ecsBlueGreenRole',
    } as any);
    expect(errors).toEqual({});
  });

  it('rejects a single field set out of the four', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      alternateTargetGroupArn: 'arn:alternate-target-group',
    } as any);
    expect(errors.alternateTargetGroupArn).toBeFalsy();
    expect(errors.productionListenerRule).toBeTruthy();
    expect(errors.testListenerRule).toBeTruthy();
    expect(errors.blueGreenRoleArn).toBeTruthy();
  });

  it('rejects three fields set out of the four', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      alternateTargetGroupArn: 'arn:alternate-target-group',
      productionListenerRule: 'arn:production-rule',
      testListenerRule: 'arn:test-rule',
    } as any);
    expect(errors.blueGreenRoleArn).toBeTruthy();
    expect(errors.alternateTargetGroupArn).toBeFalsy();
    expect(errors.productionListenerRule).toBeFalsy();
    expect(errors.testListenerRule).toBeFalsy();
  });

  it('requires all four fields when BLUE_GREEN is used with a load balancer (targetGroup) and none are set', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      deploymentStrategy: 'BLUE_GREEN',
      targetGroup: 'my-target-group',
    } as any);
    expect(errors.alternateTargetGroupArn).toBeTruthy();
    expect(errors.productionListenerRule).toBeTruthy();
    expect(errors.testListenerRule).toBeTruthy();
    expect(errors.blueGreenRoleArn).toBeTruthy();
  });

  it('requires all four fields when BLUE_GREEN is used with a load balancer (targetGroupMappings) and none are set', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      deploymentStrategy: 'BLUE_GREEN',
      targetGroupMappings: [{ targetGroup: 'my-target-group' }],
    } as any);
    expect(errors.alternateTargetGroupArn).toBeTruthy();
    expect(errors.blueGreenRoleArn).toBeTruthy();
  });

  it('does not require the fields when BLUE_GREEN is used with no load balancer', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      deploymentStrategy: 'BLUE_GREEN',
    } as any);
    expect(errors).toEqual({});
  });

  it('does not require the fields for a load-balanced ROLLING deploy', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      deploymentStrategy: 'ROLLING',
      targetGroup: 'my-target-group',
    } as any);
    expect(errors).toEqual({});
  });

  it('passes when BLUE_GREEN with a load balancer has all four fields set', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      deploymentStrategy: 'BLUE_GREEN',
      targetGroup: 'my-target-group',
      alternateTargetGroupArn: 'arn:alternate-target-group',
      productionListenerRule: 'arn:production-rule',
      testListenerRule: 'arn:test-rule',
      blueGreenRoleArn: 'arn:aws:iam::123456789012:role/ecsBlueGreenRole',
    } as any);
    expect(errors).toEqual({});
  });

  it('rejects a non-None Spinnaker strategy for native ECS', () => {
    const errors = validateEcsNativeDeployment({ cloudProvider: 'ecs-native', strategy: 'redblack' } as any);
    expect(errors.strategy).toBeTruthy();
  });

  it('validates native rolling deployment bounds', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      minimumHealthyPercent: 0,
      maximumPercent: 99,
    } as any);
    expect(errors.minimumHealthyPercent).toBeTruthy();
    expect(errors.maximumPercent).toBeTruthy();
  });

  it('requires maximum percent to be at least minimum healthy percent', () => {
    const errors = validateEcsNativeDeployment({
      cloudProvider: 'ecs-native',
      minimumHealthyPercent: 80,
      maximumPercent: 70,
    } as any);
    expect(errors.maximumPercent).toBeTruthy();
  });
});
