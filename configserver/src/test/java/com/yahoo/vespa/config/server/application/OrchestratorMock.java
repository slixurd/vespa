package com.yahoo.vespa.config.server.application;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.orchestrator.Host;
import com.yahoo.vespa.orchestrator.Orchestrator;
import com.yahoo.vespa.orchestrator.status.ApplicationInstanceStatus;
import com.yahoo.vespa.orchestrator.status.HostStatus;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * (Only the suspended applications part of this is in use)
 *
 * @author bratseth
 */
public class OrchestratorMock implements Orchestrator {

    private final Set<HostName> suspendedHosts = new HashSet<>();
    private final Set<ApplicationId> suspendedApplications = new HashSet<>();

    @Override
    public Host getHost(HostName hostName) {
        return null;
    }

    @Override
    public HostStatus getNodeStatus(HostName hostName) {
        return suspendedHosts.contains(hostName) ? HostStatus.ALLOWED_TO_BE_DOWN : HostStatus.NO_REMARKS;
    }

    @Override
    public Function<HostName, Optional<HostStatus>> getNodeStatuses() {
        return hostName -> Optional.of(getNodeStatus(hostName));
    }

    @Override
    public void setNodeStatus(HostName hostName, HostStatus state) {}

    @Override
    public void resume(HostName hostName) {
        suspendedHosts.remove(hostName);
    }

    @Override
    public void suspend(HostName hostName) {
        suspendedHosts.add(hostName);
    }

    @Override
    public ApplicationInstanceStatus getApplicationInstanceStatus(ApplicationId appId) {
        return suspendedApplications.contains(appId)
               ? ApplicationInstanceStatus.ALLOWED_TO_BE_DOWN : ApplicationInstanceStatus.NO_REMARKS;
    }

    @Override
    public Set<ApplicationId> getAllSuspendedApplications() {
        return Collections.unmodifiableSet(suspendedApplications);
    }

    @Override
    public void resume(ApplicationId appId) {
        suspendedApplications.remove(appId);
    }

    @Override
    public void suspend(ApplicationId appId) {
        suspendedApplications.add(appId);
    }

    @Override
    public void acquirePermissionToRemove(HostName hostName) {}

    @Override
    public void suspendAll(HostName parentHostname, List<HostName> hostNames) {
        hostNames.forEach(this::suspend);
    }

}
