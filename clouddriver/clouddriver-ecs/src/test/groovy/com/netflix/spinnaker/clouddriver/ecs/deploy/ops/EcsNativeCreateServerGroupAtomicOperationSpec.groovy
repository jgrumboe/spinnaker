/*
 * Copyright 2026 Spinnaker contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.ecs.deploy.ops

import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials
import com.netflix.spinnaker.clouddriver.ecs.EcsNativeServiceTag
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.CreateServerGroupDescription
import com.netflix.spinnaker.clouddriver.ecs.deploy.description.EcsNativeCreateServerGroupDescription
import com.netflix.spinnaker.clouddriver.ecs.names.EcsDefaultNamer
import com.netflix.spinnaker.clouddriver.ecs.names.EcsServerGroupName
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse
import software.amazon.awssdk.services.ecs.model.Service
import software.amazon.awssdk.services.ecs.model.TaskDefinition
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest
import software.amazon.awssdk.services.ecs.model.UpdateServiceResponse
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsResponse
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup

class EcsNativeCreateServerGroupAtomicOperationSpec extends CommonAtomicOperation {

  def 'should apply configurable deployment configuration and circuit-breaker rollback'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getMinimumHealthyPercent() >> 50
    description.getMaximumPercent() >> 150
    description.isEnableDeploymentCircuitBreaker() >> true
    description.isDeploymentCircuitBreakerRollback() >> true

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    request.deploymentConfiguration().minimumHealthyPercent() == 50
    request.deploymentConfiguration().maximumPercent() == 150
    request.deploymentConfiguration().deploymentCircuitBreaker().enable() == true
    request.deploymentConfiguration().deploymentCircuitBreaker().rollback() == true
    request.tags().any { it.key() == EcsNativeServiceTag.KEY && it.value() == EcsNativeServiceTag.VALUE }
  }

  def 'should preserve the original ecs defaults when native fields are unset'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getMinimumHealthyPercent() >> null
    description.getMaximumPercent() >> null
    description.isEnableDeploymentCircuitBreaker() >> false
    description.isDeploymentCircuitBreakerRollback() >> false

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    request.deploymentConfiguration().minimumHealthyPercent() == 100
    request.deploymentConfiguration().maximumPercent() == 200
    request.deploymentConfiguration().deploymentCircuitBreaker().enable() == false
    request.deploymentConfiguration().deploymentCircuitBreaker().rollback() == false
  }

  def 'should send deployment alarms when alarm names are configured'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getAlarmNames() >> ['myapp-high-error-rate']
    description.isDeploymentAlarmsRollback() >> true

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    request.deploymentConfiguration().alarms().alarmNames() == ['myapp-high-error-rate']
    request.deploymentConfiguration().alarms().enable() == true
    request.deploymentConfiguration().alarms().rollback() == true
  }

  def 'should not send deployment alarms when none are configured'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    request.deploymentConfiguration().alarms() == null
  }

  def 'should apply blue/green strategy and bake time to deployment configuration'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getDeploymentStrategy() >> 'BLUE_GREEN'
    description.getBakeTimeInMinutes() >> 15

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    request.deploymentConfiguration().strategyAsString() == 'BLUE_GREEN'
    request.deploymentConfiguration().bakeTimeInMinutes() == 15
  }

  def 'should attach the blue/green ALB traffic-shift config to the single target-group mapping'() {
    given:
    def loadBalancingV2 = Mock(ElasticLoadBalancingV2Client)
    loadBalancingV2.describeTargetGroups(_) >> DescribeTargetGroupsResponse.builder()
        .targetGroups(TargetGroup.builder().targetGroupArn('arn:target-group').build())
        .build()

    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> 'my-target-group'
    description.getContainerPort() >> 80
    description.getAvailabilityZones() >> ['us-west-1': ['us-west-1a']]
    description.getAlternateTargetGroupArn() >> 'arn:alternate-target-group'
    description.getProductionListenerRule() >> 'arn:production-rule'
    description.getTestListenerRule() >> 'arn:test-rule'
    description.getBlueGreenRoleArn() >> 'arn:aws:iam::123456789012:role/ecsBlueGreenRole'

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)
    operation.amazonClientProvider = amazonClientProvider
    amazonClientProvider.getElasticLoadBalancingV2Client(_, _) >> loadBalancingV2

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    request.loadBalancers().size() == 1
    def advancedConfig = request.loadBalancers().get(0).advancedConfiguration()
    advancedConfig.alternateTargetGroupArn() == 'arn:alternate-target-group'
    advancedConfig.productionListenerRule() == 'arn:production-rule'
    advancedConfig.testListenerRule() == 'arn:test-rule'
    advancedConfig.roleArn() == 'arn:aws:iam::123456789012:role/ecsBlueGreenRole'
  }

  def 'should reject a partially configured blue/green ALB traffic shift'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getAlternateTargetGroupArn() >> 'arn:alternate-target-group'

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    thrown(IllegalArgumentException)
  }

  def 'should reject a blue/green ALB traffic shift with no single target-group mapping'() {
    given:
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getAlternateTargetGroupArn() >> 'arn:alternate-target-group'
    description.getProductionListenerRule() >> 'arn:production-rule'
    description.getTestListenerRule() >> 'arn:test-rule'
    description.getBlueGreenRoleArn() >> 'arn:aws:iam::123456789012:role/ecsBlueGreenRole'

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    thrown(IllegalArgumentException)
  }

  def 'should reject blue/green on a load-balanced create when the ALB traffic-shift fields are missing'() {
    given:
    // BLUE_GREEN + a target group but none of the four ALB fields set. AWS ECS would reject this
    // with a 400 ("advancedConfiguration field is required for all loadBalancers ..."); we fail
    // fast with an actionable message instead.
    def loadBalancingV2 = Mock(ElasticLoadBalancingV2Client)
    loadBalancingV2.describeTargetGroups(_) >> DescribeTargetGroupsResponse.builder()
        .targetGroups(TargetGroup.builder().targetGroupArn('arn:target-group').build())
        .build()

    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> 'my-target-group'
    description.getContainerPort() >> 80
    description.getAvailabilityZones() >> ['us-west-1': ['us-west-1a']]
    description.getDeploymentStrategy() >> 'BLUE_GREEN'

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)
    operation.amazonClientProvider = amazonClientProvider
    amazonClientProvider.getElasticLoadBalancingV2Client(_, _) >> loadBalancingV2

    when:
    operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    def e = thrown(IllegalArgumentException)
    e.message.contains('Blue/Green')
    e.message.contains('alternateTargetGroupArn')
  }

  def 'should allow blue/green on a create with no load balancer (no ALB traffic-shift required)'() {
    given:
    // No target group -> BLUE_GREEN needs no advancedConfiguration, so this must not be rejected.
    def description = Mock(EcsNativeCreateServerGroupDescription)
    description.getApplication() >> 'mygreatapp'
    description.getStack() >> 'stack1'
    description.getFreeFormDetails() >> 'details2'
    description.getTargetGroup() >> null
    description.getDeploymentStrategy() >> 'BLUE_GREEN'

    def operation = new EcsNativeCreateServerGroupAtomicOperation(description)

    when:
    CreateServiceRequest request = operation.makeServiceRequest('task-def-arn',
        new EcsServerGroupName('mygreatapp-stack1-details2-v011'),
        1, new EcsDefaultNamer(), true)

    then:
    request.deploymentConfiguration().strategyAsString() == 'BLUE_GREEN'
    request.loadBalancers().isEmpty()
  }

  def 'should reject blue/green on a load-balanced in-place update when the ALB traffic-shift fields are missing'() {
    given:
    // The common ecs-native path: the durable service already exists, so operate() rolls it in
    // place via UpdateService. BLUE_GREEN + a declared target group but no ALB fields must fail
    // before the UpdateService call rather than surfacing as an opaque AWS 400.
    def serviceName = 'mygreatapp-stack1-details2'
    def description = new EcsNativeCreateServerGroupDescription(
        application: 'mygreatapp', stack: 'stack1', freeFormDetails: 'details2',
        ecsClusterName: 'my-cluster',
        deploymentStrategy: 'BLUE_GREEN',
        targetGroup: 'my-target-group',
        availabilityZones: ['us-west-1': ['us-west-1a']])

    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.getAmazonEcsClient() >> ecs
    operation.getCredentials() >> Mock(AmazonCredentials)
    operation.resolveTaskRoleArn(_) >> 'arn:aws:iam::123456789012:role/ecsRole'
    operation.registerTaskDefinition(ecs, _, _) >> TaskDefinition.builder().taskDefinitionArn('new-task-def-arn').build()
    ecs.describeServices(_ as DescribeServicesRequest) >> DescribeServicesResponse.builder()
        .services(Service.builder().serviceName(serviceName).status('ACTIVE').tags(EcsNativeServiceTag.tag()).build())
        .build()

    when:
    operation.operate([])

    then:
    def e = thrown(IllegalArgumentException)
    e.message.contains('Blue/Green')
    e.message.contains('alternateTargetGroupArn')
    0 * ecs.updateService(_)
  }

  def 'in-place blue/green update attaches the ALB traffic-shift config to the UpdateService load balancer'() {
    given:
    // The durable service already exists, so operate() rolls it in place. With a full blue/green
    // ALB config, the UpdateServiceRequest must carry the advancedConfiguration on its load balancer
    // -- otherwise AWS ECS rejects the deploy with a 400.
    def serviceName = 'mygreatapp-stack1-details2'
    def loadBalancingV2 = Mock(ElasticLoadBalancingV2Client)
    loadBalancingV2.describeTargetGroups(_) >> DescribeTargetGroupsResponse.builder()
        .targetGroups(TargetGroup.builder().targetGroupArn('arn:target-group').build())
        .build()

    def description = new EcsNativeCreateServerGroupDescription(
        application: 'mygreatapp', stack: 'stack1', freeFormDetails: 'details2',
        ecsClusterName: 'my-cluster',
        deploymentStrategy: 'BLUE_GREEN',
        targetGroup: 'my-target-group',
        containerPort: 80,
        availabilityZones: ['us-west-1': ['us-west-1a']],
        alternateTargetGroupArn: 'arn:alternate-target-group',
        productionListenerRule: 'arn:production-rule',
        testListenerRule: 'arn:test-rule',
        blueGreenRoleArn: 'arn:aws:iam::123456789012:role/ecsBlueGreenRole')

    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.amazonClientProvider = amazonClientProvider
    operation.getAmazonEcsClient() >> ecs
    operation.getCredentials() >> Mock(AmazonCredentials)
    operation.resolveTaskRoleArn(_) >> 'arn:aws:iam::123456789012:role/ecsRole'
    operation.registerTaskDefinition(ecs, _, _) >> TaskDefinition.builder().taskDefinitionArn('new-task-def-arn').build()
    amazonClientProvider.getElasticLoadBalancingV2Client(_, _) >> loadBalancingV2
    ecs.describeServices(_ as DescribeServicesRequest) >> DescribeServicesResponse.builder()
        .services(Service.builder().serviceName(serviceName).status('ACTIVE').tags(EcsNativeServiceTag.tag()).build())
        .build()

    when:
    operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.service() == serviceName &&
          req.deploymentConfiguration().strategyAsString() == 'BLUE_GREEN' &&
          req.loadBalancers().size() == 1 &&
          req.loadBalancers().get(0).advancedConfiguration().alternateTargetGroupArn() == 'arn:alternate-target-group' &&
          req.loadBalancers().get(0).advancedConfiguration().productionListenerRule() == 'arn:production-rule' &&
          req.loadBalancers().get(0).advancedConfiguration().testListenerRule() == 'arn:test-rule' &&
          req.loadBalancers().get(0).advancedConfiguration().roleArn() == 'arn:aws:iam::123456789012:role/ecsBlueGreenRole'
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
  }

  def 'in-place rolling update leaves the UpdateService load balancers untouched'() {
    given:
    // A plain rolling in-place update must not (re)send loadBalancers -- it only changes the task
    // definition and deployment config, not the service's load-balancer wiring.
    def serviceName = 'mygreatapp-stack1-details2'
    def description = new EcsNativeCreateServerGroupDescription(
        application: 'mygreatapp', stack: 'stack1', freeFormDetails: 'details2',
        ecsClusterName: 'my-cluster',
        deploymentStrategy: 'ROLLING',
        targetGroup: 'my-target-group',
        availabilityZones: ['us-west-1': ['us-west-1a']])

    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.amazonClientProvider = amazonClientProvider
    operation.getAmazonEcsClient() >> ecs
    operation.getCredentials() >> Mock(AmazonCredentials)
    operation.resolveTaskRoleArn(_) >> 'arn:aws:iam::123456789012:role/ecsRole'
    operation.registerTaskDefinition(ecs, _, _) >> TaskDefinition.builder().taskDefinitionArn('new-task-def-arn').build()
    ecs.describeServices(_ as DescribeServicesRequest) >> DescribeServicesResponse.builder()
        .services(Service.builder().serviceName(serviceName).status('ACTIVE').tags(EcsNativeServiceTag.tag()).build())
        .build()

    when:
    operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.service() == serviceName && !req.hasLoadBalancers()
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
    // No ELB lookup happens for a rolling in-place update.
    0 * amazonClientProvider.getElasticLoadBalancingV2Client(_, _)
  }

  def 'resolveExistingServiceName returns the fixed service name when that ECS service already exists'() {
    given:
    // ecs-native computes the fixed (unversioned) name and asks ECS whether it exists.
    def description = new EcsNativeCreateServerGroupDescription(
        application: 'myapp', stack: 'stack', freeFormDetails: 'web', ecsClusterName: 'my-cluster')
    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.getAmazonEcsClient() >> ecs

    when:
    def resolved = operation.resolveExistingServiceName()

    then:
    1 * ecs.describeServices({ DescribeServicesRequest req ->
      req.cluster() == 'my-cluster' && req.services() == ['myapp-stack-web']
    } as DescribeServicesRequest) >> DescribeServicesResponse.builder()
        .services(Service.builder().serviceName('myapp-stack-web').status('ACTIVE').tags(EcsNativeServiceTag.tag()).build())
        .build()
    resolved == 'myapp-stack-web'
  }

  def 'resolveExistingServiceName returns null when the service does not exist (first deploy) or is INACTIVE'() {
    given:
    def description = new EcsNativeCreateServerGroupDescription(
        application: 'myapp', stack: 'stack', freeFormDetails: 'web', ecsClusterName: 'my-cluster')
    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.getAmazonEcsClient() >> ecs

    when:
    def resolved = operation.resolveExistingServiceName()

    then:
    1 * ecs.describeServices(_ as DescribeServicesRequest) >> DescribeServicesResponse.builder()
        .services(services)
        .build()
    resolved == expected

    where:
    services                                                                              || expected
    []                                                                                    || null
    [Service.builder().serviceName('myapp-stack-web').status('INACTIVE').build()]         || null
    [Service.builder().serviceName('myapp-stack-web').status('ACTIVE').tags(EcsNativeServiceTag.tag()).build()]           || 'myapp-stack-web'
  }

  def 'refuses to adopt an untagged fixed-name service'() {
    given:
    def description = new EcsNativeCreateServerGroupDescription(
        application: 'myapp', stack: 'stack', freeFormDetails: 'web', ecsClusterName: 'my-cluster')
    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.getAmazonEcsClient() >> ecs
    ecs.describeServices(_ as DescribeServicesRequest) >> DescribeServicesResponse.builder()
        .services(Service.builder().serviceName('myapp-stack-web').status('ACTIVE').build())
        .build()

    when:
    operation.resolveExistingServiceName()

    then:
    def exception = thrown(IllegalStateException)
    exception.message.contains('not marked as owned by ecs-native')
  }

  def 'operate always rolls the existing durable service in place via UpdateService (no CreateService)'() {
    given:
    def serviceName = 'mygreatapp-stack1-details2'
    def description = new EcsNativeCreateServerGroupDescription(
        application: 'mygreatapp', stack: 'stack1', freeFormDetails: 'details2',
        ecsClusterName: 'my-cluster',
        minimumHealthyPercent: 50,
        maximumPercent: 150,
        enableDeploymentCircuitBreaker: true,
        deploymentCircuitBreakerRollback: true,
        availabilityZones: ['us-west-1': ['us-west-1a']])

    def operation = Spy(EcsNativeCreateServerGroupAtomicOperation, constructorArgs: [description])
    operation.getAmazonEcsClient() >> ecs
    operation.getCredentials() >> Mock(AmazonCredentials)
    operation.resolveTaskRoleArn(_) >> 'arn:aws:iam::123456789012:role/ecsRole'
    operation.registerTaskDefinition(ecs, _, _) >> TaskDefinition.builder().taskDefinitionArn('new-task-def-arn').build()
    // The fixed-name service already exists -> in-place update.
    ecs.describeServices(_ as DescribeServicesRequest) >> DescribeServicesResponse.builder()
        .services(Service.builder().serviceName(serviceName).status('ACTIVE').tags(EcsNativeServiceTag.tag()).build())
        .build()

    when:
    def result = operation.operate([])

    then:
    1 * ecs.updateService({ UpdateServiceRequest req ->
      req.cluster() == 'my-cluster' &&
          req.service() == serviceName &&
          req.taskDefinition() == 'new-task-def-arn' &&
          req.forceNewDeployment() == true &&
          req.deploymentConfiguration().minimumHealthyPercent() == 50 &&
          req.deploymentConfiguration().maximumPercent() == 150 &&
          req.deploymentConfiguration().deploymentCircuitBreaker().enable() == true &&
          req.deploymentConfiguration().deploymentCircuitBreaker().rollback() == true
    } as UpdateServiceRequest) >> UpdateServiceResponse.builder()
        .service(Service.builder().serviceName(serviceName).build())
        .build()
    0 * ecs.createService(_)
    result.serverGroupNameByRegion == ['us-west-1': serviceName]
  }
}
