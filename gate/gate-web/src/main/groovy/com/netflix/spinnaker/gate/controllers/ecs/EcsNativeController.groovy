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

package com.netflix.spinnaker.gate.controllers.ecs

import com.netflix.spinnaker.gate.services.EcsNativeService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*

@RestController
class EcsNativeController {

  @Autowired
  EcsNativeService ecsNativeService

  @Operation(
    summary = "Retrieve the ACTIVE task-definition revisions for an ecs-native service's family, newest first")
  @RequestMapping(
    value = "/ecs-native/serverGroups/{account}/{region}/{serverGroupName}/taskDefinitions",
    method = RequestMethod.GET)
  List getTaskDefinitions(@PathVariable String account,
                          @PathVariable String region,
                          @PathVariable String serverGroupName) {
    ecsNativeService.getTaskDefinitions(account, region, serverGroupName)
  }
}
