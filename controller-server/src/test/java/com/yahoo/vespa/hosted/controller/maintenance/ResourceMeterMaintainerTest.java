// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceSnapshot;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockMeteringClient;
import com.yahoo.vespa.hosted.controller.integration.MetricsMock;
import com.yahoo.vespa.hosted.controller.integration.ZoneApiMock;
import org.junit.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * @author olaa
 */
public class ResourceMeterMaintainerTest {

    private final ControllerTester tester = new ControllerTester();
    private final double DELTA = Double.MIN_VALUE;
    private MockMeteringClient snapshotConsumer = new MockMeteringClient();
    private MetricsMock metrics = new MetricsMock();

    @Test
    public void testMaintainer() {
        var awsZone = ZoneApiMock.newBuilder().withId("prod.aws-us-east-1").withCloud("aws").build();
        tester.zoneRegistry().setZones(
                ZoneApiMock.newBuilder().withId("prod.us-east-3").build(),
                ZoneApiMock.newBuilder().withId("prod.us-west-1").build(),
                ZoneApiMock.newBuilder().withId("prod.us-central-1").build(),
                awsZone);
        tester.configServer().nodeRepository().addFixedNodes(awsZone.getId());

        ResourceMeterMaintainer resourceMeterMaintainer = new ResourceMeterMaintainer(tester.controller(), Duration.ofMinutes(5), new JobControl(tester.curator()), metrics, snapshotConsumer);
        resourceMeterMaintainer.maintain();
        Collection<ResourceSnapshot> consumedResources = snapshotConsumer.consumedResources();

        // The mocked repository contains two applications, so we should also consume two ResourceSnapshots
        assertEquals(2, consumedResources.size());
        ResourceSnapshot app1 = consumedResources.stream().filter(snapshot -> snapshot.getApplicationId().equals(ApplicationId.from("tenant1", "app1", "default"))).findFirst().orElseThrow();
        ResourceSnapshot app2 = consumedResources.stream().filter(snapshot -> snapshot.getApplicationId().equals(ApplicationId.from("tenant2", "app2", "default"))).findFirst().orElseThrow();

        assertEquals(24, app1.getCpuCores(), DELTA);
        assertEquals(24, app1.getMemoryGb(), DELTA);
        assertEquals(500, app1.getDiskGb(), DELTA);

        assertEquals(40, app2.getCpuCores(), DELTA);
        assertEquals(24, app2.getMemoryGb(), DELTA);
        assertEquals(500, app2.getDiskGb(), DELTA);

        assertEquals(tester.clock().millis()/1000, metrics.getMetric("metering_last_reported"));
        assertEquals(1112.0d, (Double) metrics.getMetric("metering_total_reported"), DELTA);
    }

}
