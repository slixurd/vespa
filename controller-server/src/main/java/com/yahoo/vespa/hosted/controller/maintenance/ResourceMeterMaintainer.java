// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.Node;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.NodeRepository;
import com.yahoo.vespa.hosted.controller.api.integration.resource.MeteringClient;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Creates a ResourceSnapshot per application, which is then passed on to a MeteringClient
 *
 * @author olaa
 */
public class ResourceMeterMaintainer extends Maintainer {

    private final Clock clock;
    private final Metric metric;
    private final NodeRepository nodeRepository;
    private final MeteringClient meteringClient;

    private static final String METERING_LAST_REPORTED = "metering_last_reported";
    private static final String METERING_TOTAL_REPORTED = "metering_total_reported";

    @SuppressWarnings("WeakerAccess")
    public ResourceMeterMaintainer(Controller controller,
                                   Duration interval,
                                   JobControl jobControl,
                                   Metric metric,
                                   MeteringClient meteringClient) {
        super(controller, interval, jobControl, null, SystemName.all());
        this.clock = controller.clock();
        this.nodeRepository = controller.serviceRegistry().configServer().nodeRepository();
        this.metric = metric;
        this.meteringClient = meteringClient;
    }

    @Override
    protected void maintain() {
        Collection<ResourceSnapshot> resourceSnapshots = getResourceSnapshots(allocatedNodes());
        meteringClient.consume(resourceSnapshots);

        metric.set(METERING_LAST_REPORTED, clock.millis() / 1000, metric.createContext(Collections.emptyMap()));
        // total metered resource usage, for alerting on drastic changes
        metric.set(METERING_TOTAL_REPORTED,
                   resourceSnapshots.stream().mapToDouble(r -> r.getCpuCores() + r.getMemoryGb() + r.getDiskGb()).sum(),
                   metric.createContext(Collections.emptyMap()));
    }

    private List<Node> allocatedNodes() {
        return controller().zoneRegistry().zones()
                .ofCloud(CloudName.from("aws"))
                .reachable().zones().stream()
                .flatMap(zone -> nodeRepository.list(zone.getId()).stream())
                .filter(node -> node.owner().isPresent())
                .filter(node -> ! node.owner().get().tenant().value().equals("hosted-vespa"))
                .collect(Collectors.toList());
    }

    private Collection<ResourceSnapshot> getResourceSnapshots(List<Node> nodes) {
        return nodes.stream()
                    .collect(Collectors.groupingBy(node -> node.owner().get(),
                                                   Collectors.collectingAndThen(Collectors.toList(),
                                                                                nodeList -> ResourceSnapshot.from(nodeList,
                                                                                                                  clock.instant()))
                                    )).values();
    }

}
