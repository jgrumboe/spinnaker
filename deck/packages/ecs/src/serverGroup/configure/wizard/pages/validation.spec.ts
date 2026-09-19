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
});
