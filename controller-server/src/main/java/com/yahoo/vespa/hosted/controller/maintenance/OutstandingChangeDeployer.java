// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.Controller;

import java.time.Duration;

/**
 * Deploys application changes which have been postponed due to an ongoing upgrade, or a block window.
 *
 * @author bratseth
 */
public class OutstandingChangeDeployer extends Maintainer {

    public OutstandingChangeDeployer(Controller controller, Duration interval, JobControl jobControl) {
        super(controller, interval, jobControl);
    }

    @Override
    protected void maintain() {
        for (Instance instance : controller().applications().asList()) {
            if (instance.outstandingChange().hasTargets()
                && instance.deploymentSpec().canChangeRevisionAt(controller().clock().instant())) {
                controller().applications().deploymentTrigger().triggerChange(instance.id(),
                                                                              instance.outstandingChange());
            }
        }
    }

}
