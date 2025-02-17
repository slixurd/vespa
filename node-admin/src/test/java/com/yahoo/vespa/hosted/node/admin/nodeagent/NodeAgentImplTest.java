// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.nodeagent;

import com.yahoo.component.Version;
import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.dockerapi.Container;
import com.yahoo.vespa.hosted.dockerapi.ContainerName;
import com.yahoo.vespa.hosted.dockerapi.ContainerResources;
import com.yahoo.vespa.hosted.dockerapi.exception.DockerException;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeAttributes;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeRepository;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeState;
import com.yahoo.vespa.hosted.node.admin.configserver.orchestrator.Orchestrator;
import com.yahoo.vespa.hosted.node.admin.configserver.orchestrator.OrchestratorException;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperations;
import com.yahoo.vespa.hosted.node.admin.maintenance.StorageMaintainer;
import com.yahoo.vespa.hosted.node.admin.maintenance.acl.AclMaintainer;
import com.yahoo.vespa.hosted.node.admin.maintenance.identity.CredentialsMaintainer;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.ConvergenceException;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Øyvind Bakksjø
 */
public class NodeAgentImplTest {
    private static final double MIN_CPU_CORES = 2;
    private static final double MIN_MAIN_MEMORY_AVAILABLE_GB = 16;
    private static final double MIN_DISK_AVAILABLE_GB = 250;
    private static final Version vespaVersion = Version.fromString("1.2.3");

    private final String hostName = "host1.test.yahoo.com";
    private final NodeSpec.Builder nodeBuilder = new NodeSpec.Builder()
            .hostname(hostName)
            .type(NodeType.tenant)
            .flavor("docker")
            .vcpus(MIN_CPU_CORES)
            .memoryGb(MIN_MAIN_MEMORY_AVAILABLE_GB)
            .diskGb(MIN_DISK_AVAILABLE_GB);

    private final NodeAgentContextSupplier contextSupplier = mock(NodeAgentContextSupplier.class);
    private final DockerImage dockerImage = DockerImage.fromString("dockerImage");
    private final DockerOperations dockerOperations = mock(DockerOperations.class);
    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final Orchestrator orchestrator = mock(Orchestrator.class);
    private final StorageMaintainer storageMaintainer = mock(StorageMaintainer.class);
    private final AclMaintainer aclMaintainer = mock(AclMaintainer.class);
    private final HealthChecker healthChecker = mock(HealthChecker.class);
    private final CredentialsMaintainer credentialsMaintainer = mock(CredentialsMaintainer.class);
    private final InMemoryFlagSource flagSource = new InMemoryFlagSource();


    @Test
    public void upToDateContainerIsUntouched() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);
        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(187500000000L));

        nodeAgent.doConverge(context);

        verify(dockerOperations, never()).removeContainer(eq(context), any());
        verify(orchestrator, never()).suspend(any(String.class));
        verify(dockerOperations, never()).pullImageAsyncIfNeeded(any());
        verify(storageMaintainer, never()).removeOldFilesFromNode(eq(context));

        final InOrder inOrder = inOrder(dockerOperations, orchestrator, nodeRepository);
        // TODO: Verify this isn't run unless 1st time
        inOrder.verify(dockerOperations, never()).startServices(eq(context));
        inOrder.verify(dockerOperations, times(1)).resumeNode(eq(context));
        inOrder.verify(orchestrator).resume(hostName);
    }

    @Test
    public void verifyRemoveOldFilesIfDiskFull() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);
        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(217432719360L));

        nodeAgent.doConverge(context);

        verify(storageMaintainer, times(1)).removeOldFilesFromNode(eq(context));
    }

    @Test
    public void startsAfterStoppingServices() {
        final InOrder inOrder = inOrder(dockerOperations);
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);
        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(187500000000L));

        nodeAgent.doConverge(context);
        inOrder.verify(dockerOperations, never()).startServices(eq(context));
        inOrder.verify(dockerOperations, times(1)).resumeNode(eq(context));

        nodeAgent.stopForHostSuspension(context);
        nodeAgent.doConverge(context);
        inOrder.verify(dockerOperations, never()).startServices(eq(context));
        inOrder.verify(dockerOperations, times(1)).resumeNode(eq(context)); // Expect a resume, but no start services

        // No new suspends/stops, so no need to resume/start
        nodeAgent.doConverge(context);
        inOrder.verify(dockerOperations, never()).startServices(eq(context));
        inOrder.verify(dockerOperations, never()).resumeNode(eq(context));

        nodeAgent.stopForHostSuspension(context);
        nodeAgent.doConverge(context);
        inOrder.verify(dockerOperations, times(1)).createContainer(eq(context), any(), any());
        inOrder.verify(dockerOperations, times(1)).startContainer(eq(context));
        inOrder.verify(dockerOperations, times(0)).startServices(eq(context)); // done as part of startContainer
        inOrder.verify(dockerOperations, times(1)).resumeNode(eq(context));
    }

    @Test
    public void absentContainerCausesStart() {
        final Optional<Long> restartGeneration = Optional.of(1L);
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .wantedRestartGeneration(restartGeneration.get())
                .currentRestartGeneration(restartGeneration.get())
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(null, false);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));
        when(dockerOperations.pullImageAsyncIfNeeded(eq(dockerImage))).thenReturn(false);
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(201326592000L));

        nodeAgent.doConverge(context);

        verify(dockerOperations, never()).removeContainer(eq(context), any());
        verify(dockerOperations, never()).startServices(any());
        verify(orchestrator, never()).suspend(any(String.class));

        final InOrder inOrder = inOrder(dockerOperations, orchestrator, nodeRepository, aclMaintainer, healthChecker);
        inOrder.verify(dockerOperations, times(1)).pullImageAsyncIfNeeded(eq(dockerImage));
        inOrder.verify(dockerOperations, times(1)).createContainer(eq(context), any(), any());
        inOrder.verify(dockerOperations, times(1)).startContainer(eq(context));
        inOrder.verify(aclMaintainer, times(1)).converge(eq(context));
        inOrder.verify(dockerOperations, times(1)).resumeNode(eq(context));
        inOrder.verify(healthChecker, times(1)).verifyHealth(eq(context));
        inOrder.verify(nodeRepository).updateNodeAttributes(
                hostName, new NodeAttributes().withDockerImage(dockerImage).withVespaVersion(dockerImage.tagAsVersion()));
        inOrder.verify(orchestrator).resume(hostName);
    }

    @Test
    public void containerIsNotStoppedIfNewImageMustBePulled() {
        final DockerImage newDockerImage = DockerImage.fromString("new-image");
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(newDockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));
        when(dockerOperations.pullImageAsyncIfNeeded(any())).thenReturn(true);
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(201326592000L));

        nodeAgent.doConverge(context);

        verify(orchestrator, never()).suspend(any(String.class));
        verify(orchestrator, never()).resume(any(String.class));
        verify(dockerOperations, never()).removeContainer(eq(context), any());

        final InOrder inOrder = inOrder(dockerOperations);
        inOrder.verify(dockerOperations, times(1)).pullImageAsyncIfNeeded(eq(newDockerImage));
    }

    @Test
    public void containerIsUpdatedIfCpuChanged() {
        NodeSpec.Builder specBuilder = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion);

        NodeAgentContext firstContext = createContext(specBuilder.build());
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(dockerOperations.pullImageAsyncIfNeeded(any())).thenReturn(true);
        when(storageMaintainer.getDiskUsageFor(any())).thenReturn(Optional.of(201326592000L));

        nodeAgent.doConverge(firstContext);
        NodeAgentContext secondContext = createContext(specBuilder.diskGb(200).build());
        nodeAgent.doConverge(secondContext);
        NodeAgentContext thirdContext = createContext(specBuilder.vcpus(4).build());
        nodeAgent.doConverge(thirdContext);
        ContainerResources resourcesAfterThird = ContainerResources.from(0, 4, 16);
        mockGetContainer(dockerImage, resourcesAfterThird, true);

        InOrder inOrder = inOrder(orchestrator, dockerOperations);
        inOrder.verify(orchestrator).resume(any(String.class));
        inOrder.verify(orchestrator).resume(any(String.class));
        inOrder.verify(orchestrator).suspend(any(String.class));
        inOrder.verify(dockerOperations).updateContainer(eq(thirdContext), eq(resourcesAfterThird));
        inOrder.verify(dockerOperations, never()).removeContainer(any(), any());
        inOrder.verify(dockerOperations, never()).startContainer(any());
        inOrder.verify(orchestrator).resume(any(String.class));

        // No changes
        nodeAgent.converge(thirdContext);
        inOrder.verify(orchestrator, never()).suspend(any(String.class));
        inOrder.verify(dockerOperations, never()).updateContainer(eq(thirdContext), any());
        inOrder.verify(dockerOperations, never()).removeContainer(any(), any());
        inOrder.verify(orchestrator).resume(any(String.class));

        // Set the feature flag
        flagSource.withDoubleFlag(Flags.CONTAINER_CPU_CAP.id(), 2.3);

        nodeAgent.doConverge(thirdContext);
        inOrder.verify(dockerOperations).updateContainer(eq(thirdContext), eq(ContainerResources.from(9.2, 4, 16)));
        inOrder.verify(orchestrator).resume(any(String.class));
    }

    @Test
    public void containerIsRestartedIfMemoryChanged() {
        NodeSpec.Builder specBuilder = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion);

        NodeAgentContext firstContext = createContext(specBuilder.build());
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(dockerOperations.pullImageAsyncIfNeeded(any())).thenReturn(true);
        when(storageMaintainer.getDiskUsageFor(any())).thenReturn(Optional.of(201326592000L));

        nodeAgent.doConverge(firstContext);
        NodeAgentContext secondContext = createContext(specBuilder.memoryGb(20).build());
        nodeAgent.doConverge(secondContext);
        ContainerResources resourcesAfterThird = ContainerResources.from(0, 2, 20);
        mockGetContainer(dockerImage, resourcesAfterThird, true);

        InOrder inOrder = inOrder(orchestrator, dockerOperations);
        inOrder.verify(orchestrator).resume(any(String.class));
        inOrder.verify(orchestrator).suspend(any(String.class));
        inOrder.verify(dockerOperations).removeContainer(eq(secondContext), any());
        inOrder.verify(dockerOperations, times(1)).createContainer(eq(secondContext), any(), any());
        inOrder.verify(dockerOperations).startContainer(eq(secondContext));
        inOrder.verify(dockerOperations, never()).updateContainer(any(), any());

        nodeAgent.doConverge(secondContext);
        inOrder.verify(orchestrator).resume(any(String.class));
        inOrder.verify(dockerOperations, never()).updateContainer(any(), any());
        inOrder.verify(dockerOperations, never()).removeContainer(any(), any());
    }

    @Test
    public void noRestartIfOrchestratorSuspendFails() {
        final long wantedRestartGeneration = 2;
        final long currentRestartGeneration = 1;
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .wantedRestartGeneration(wantedRestartGeneration)
                .currentRestartGeneration(currentRestartGeneration)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        doThrow(new OrchestratorException("Denied")).when(orchestrator).suspend(eq(hostName));
        try {
            nodeAgent.doConverge(context);
            fail("Expected to throw an exception");
        } catch (OrchestratorException ignored) { }

        verify(dockerOperations, never()).createContainer(eq(context), any(), any());
        verify(dockerOperations, never()).startContainer(eq(context));
        verify(orchestrator, never()).resume(any(String.class));
        verify(nodeRepository, never()).updateNodeAttributes(any(String.class), any(NodeAttributes.class));

        // Verify aclMaintainer is called even if suspension fails
        verify(aclMaintainer, times(1)).converge(eq(context));
    }

    @Test
    public void recreatesContainerIfRebootWanted() {
        final long wantedRebootGeneration = 2;
        final long currentRebootGeneration = 1;
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .wantedRebootGeneration(wantedRebootGeneration)
                .currentRebootGeneration(currentRebootGeneration)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));
        when(dockerOperations.pullImageAsyncIfNeeded(eq(dockerImage))).thenReturn(false);
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(201326592000L));
        doThrow(new ConvergenceException("Connection refused")).doNothing()
                .when(healthChecker).verifyHealth(eq(context));

        try {
            nodeAgent.doConverge(context);
        } catch (ConvergenceException ignored) {}

        // First time we fail to resume because health verification fails
        verify(orchestrator, times(1)).suspend(eq(hostName));
        verify(dockerOperations, times(1)).removeContainer(eq(context), any());
        verify(dockerOperations, times(1)).createContainer(eq(context), any(), any());
        verify(dockerOperations, times(1)).startContainer(eq(context));
        verify(orchestrator, never()).resume(eq(hostName));
        verify(nodeRepository, never()).updateNodeAttributes(any(), any());

        nodeAgent.doConverge(context);

        // Do not reboot the container again
        verify(dockerOperations, times(1)).removeContainer(eq(context), any());
        verify(dockerOperations, times(1)).createContainer(eq(context), any(), any());
        verify(orchestrator, times(1)).resume(eq(hostName));
        verify(nodeRepository, times(1)).updateNodeAttributes(eq(hostName), eq(new NodeAttributes()
                .withRebootGeneration(wantedRebootGeneration)));
    }

    @Test
    public void failedNodeRunningContainerShouldStillBeRunning() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.failed)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));

        nodeAgent.doConverge(context);

        verify(dockerOperations, never()).removeContainer(eq(context), any());
        verify(orchestrator, never()).resume(any(String.class));
        verify(nodeRepository, never()).updateNodeAttributes(eq(hostName), any());
    }

    @Test
    public void readyNodeLeadsToNoAction() {
        final NodeSpec node = nodeBuilder
                .state(NodeState.ready)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(null,false);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));

        nodeAgent.doConverge(context);
        nodeAgent.doConverge(context);
        nodeAgent.doConverge(context);

        // Should only be called once, when we initialize
        verify(dockerOperations, times(1)).getContainer(eq(context));
        verify(dockerOperations, never()).removeContainer(eq(context), any());
        verify(dockerOperations, never()).createContainer(eq(context), any(), any());
        verify(dockerOperations, never()).startContainer(eq(context));
        verify(orchestrator, never()).resume(any(String.class));
        verify(nodeRepository, never()).updateNodeAttributes(eq(hostName), any());
    }

    @Test
    public void inactiveNodeRunningContainerShouldStillBeRunning() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.inactive)
                .wantedVespaVersion(vespaVersion)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));

        nodeAgent.doConverge(context);

        final InOrder inOrder = inOrder(storageMaintainer, dockerOperations);
        inOrder.verify(dockerOperations, never()).removeContainer(eq(context), any());

        verify(orchestrator, never()).resume(any(String.class));
        verify(nodeRepository, never()).updateNodeAttributes(eq(hostName), any());
    }

    @Test
    public void reservedNodeDoesNotUpdateNodeRepoWithVersion() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .state(NodeState.reserved)
                .wantedVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(null, false);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));

        nodeAgent.doConverge(context);

        verify(nodeRepository, never()).updateNodeAttributes(eq(hostName), any());
    }

    private void nodeRunningContainerIsTakenDownAndCleanedAndRecycled(NodeState nodeState, Optional<Long> wantedRestartGeneration) {
        wantedRestartGeneration.ifPresent(restartGeneration -> nodeBuilder
                .wantedRestartGeneration(restartGeneration)
                .currentRestartGeneration(restartGeneration));

        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(nodeState)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));

        nodeAgent.doConverge(context);

        final InOrder inOrder = inOrder(storageMaintainer, dockerOperations, nodeRepository);
        inOrder.verify(dockerOperations, times(1)).stopServices(eq(context));
        inOrder.verify(storageMaintainer, times(1)).handleCoreDumpsForContainer(eq(context), any());
        inOrder.verify(dockerOperations, times(1)).removeContainer(eq(context), any());
        inOrder.verify(storageMaintainer, times(1)).archiveNodeStorage(eq(context));
        inOrder.verify(nodeRepository, times(1)).setNodeState(eq(hostName), eq(NodeState.ready));

        verify(dockerOperations, never()).createContainer(eq(context), any(), any());
        verify(dockerOperations, never()).startContainer(eq(context));
        verify(dockerOperations, never()).suspendNode(eq(context));
        verify(dockerOperations, times(1)).stopServices(eq(context));
        verify(orchestrator, never()).resume(any(String.class));
        verify(orchestrator, never()).suspend(any(String.class));
        // current Docker image and vespa version should be cleared
        verify(nodeRepository, times(1)).updateNodeAttributes(
                eq(hostName), eq(new NodeAttributes().withDockerImage(DockerImage.EMPTY).withVespaVersion(Version.emptyVersion)));
    }

    @Test
    public void dirtyNodeRunningContainerIsTakenDownAndCleanedAndRecycled() {
        nodeRunningContainerIsTakenDownAndCleanedAndRecycled(NodeState.dirty, Optional.of(1L));
    }

    @Test
    public void dirtyNodeRunningContainerIsTakenDownAndCleanedAndRecycledNoRestartGeneration() {
        nodeRunningContainerIsTakenDownAndCleanedAndRecycled(NodeState.dirty, Optional.empty());
    }

    @Test
    public void provisionedNodeIsMarkedAsDirty() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .state(NodeState.provisioned)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(null, false);
        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));

        nodeAgent.doConverge(context);
        verify(nodeRepository, times(1)).setNodeState(eq(hostName), eq(NodeState.dirty));
    }

    @Test
    public void testRestartDeadContainerAfterNodeAdminRestart() {
        final NodeSpec node = nodeBuilder
                .currentDockerImage(dockerImage)
                .wantedDockerImage(dockerImage)
                .state(NodeState.active)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, false);

        when(nodeRepository.getOptionalNode(eq(hostName))).thenReturn(Optional.of(node));
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(201326592000L));

        nodeAgent.doConverge(context);

        verify(dockerOperations, times(1)).removeContainer(eq(context), any());
        verify(dockerOperations, times(1)).createContainer(eq(context), any(), any());
        verify(dockerOperations, times(1)).startContainer(eq(context));
    }

    @Test
    public void resumeProgramRunsUntilSuccess() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .currentDockerImage(dockerImage)
                .state(NodeState.active)
                .currentVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(dockerImage, true);

        when(nodeRepository.getOptionalNode(eq(hostName))).thenReturn(Optional.of(node));
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(201326592000L));

        final InOrder inOrder = inOrder(orchestrator, dockerOperations, nodeRepository);
        doThrow(new RuntimeException("Failed 1st time"))
                .doNothing()
                .when(dockerOperations).resumeNode(eq(context));

        // 1st try
        try {
            nodeAgent.doConverge(context);
            fail("Expected to throw an exception");
        } catch (RuntimeException ignored) { }

        inOrder.verify(dockerOperations, times(1)).resumeNode(any());
        inOrder.verifyNoMoreInteractions();

        // 2nd try
        nodeAgent.doConverge(context);

        inOrder.verify(dockerOperations).resumeNode(any());
        inOrder.verify(orchestrator).resume(hostName);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void start_container_subtask_failure_leads_to_container_restart() {
        final NodeSpec node = nodeBuilder
                .wantedDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = spy(makeNodeAgent(null, false));

        when(dockerOperations.pullImageAsyncIfNeeded(eq(dockerImage))).thenReturn(false);
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(201326592000L));
        doThrow(new DockerException("Failed to set up network")).doNothing().when(dockerOperations).startContainer(eq(context));

        try {
            nodeAgent.doConverge(context);
            fail("Expected to get DockerException");
        } catch (DockerException ignored) { }

        verify(dockerOperations, never()).removeContainer(eq(context), any());
        verify(dockerOperations, times(1)).createContainer(eq(context), any(), any());
        verify(dockerOperations, times(1)).startContainer(eq(context));
        verify(nodeAgent, never()).resumeNodeIfNeeded(any());

        // The docker container was actually started and is running, but subsequent exec calls to set up
        // networking failed
        mockGetContainer(dockerImage, true);
        nodeAgent.doConverge(context);

        verify(dockerOperations, times(1)).removeContainer(eq(context), any());
        verify(dockerOperations, times(2)).createContainer(eq(context), any(), any());
        verify(dockerOperations, times(2)).startContainer(eq(context));
        verify(nodeAgent, times(1)).resumeNodeIfNeeded(any());
    }

    @Test
    public void testRunningConfigServer() {
        final NodeSpec node = nodeBuilder
                .type(NodeType.config)
                .wantedDockerImage(dockerImage)
                .state(NodeState.active)
                .wantedVespaVersion(vespaVersion)
                .build();

        NodeAgentContext context = createContext(node);
        NodeAgentImpl nodeAgent = makeNodeAgent(null, false);

        when(nodeRepository.getOptionalNode(hostName)).thenReturn(Optional.of(node));
        when(dockerOperations.pullImageAsyncIfNeeded(eq(dockerImage))).thenReturn(false);
        when(storageMaintainer.getDiskUsageFor(eq(context))).thenReturn(Optional.of(201326592000L));

        nodeAgent.doConverge(context);

        verify(dockerOperations, never()).removeContainer(eq(context), any());
        verify(orchestrator, never()).suspend(any(String.class));

        final InOrder inOrder = inOrder(dockerOperations, orchestrator, nodeRepository, aclMaintainer);
        inOrder.verify(dockerOperations, times(1)).pullImageAsyncIfNeeded(eq(dockerImage));
        inOrder.verify(dockerOperations, times(1)).createContainer(eq(context), any(), any());
        inOrder.verify(dockerOperations, times(1)).startContainer(eq(context));
        inOrder.verify(aclMaintainer, times(1)).converge(eq(context));
        inOrder.verify(dockerOperations, times(1)).resumeNode(eq(context));
        inOrder.verify(nodeRepository).updateNodeAttributes(
                hostName, new NodeAttributes().withDockerImage(dockerImage).withVespaVersion(dockerImage.tagAsVersion()));
        inOrder.verify(orchestrator).resume(hostName);
    }

    private NodeAgentImpl makeNodeAgent(DockerImage dockerImage, boolean isRunning) {
        mockGetContainer(dockerImage, isRunning);

        return new NodeAgentImpl(contextSupplier, nodeRepository, orchestrator, dockerOperations,
                storageMaintainer, flagSource, Optional.of(credentialsMaintainer), Optional.of(aclMaintainer),
                Optional.of(healthChecker));
    }

    private void mockGetContainer(DockerImage dockerImage, boolean isRunning) {
        mockGetContainer(dockerImage, ContainerResources.from(0, MIN_CPU_CORES, MIN_MAIN_MEMORY_AVAILABLE_GB), isRunning);
    }

    private void mockGetContainer(DockerImage dockerImage, ContainerResources containerResources, boolean isRunning) {
        doAnswer(invoc -> {
            NodeAgentContext context = invoc.getArgument(0);
            if (!hostName.equals(context.hostname().value()))
                throw new IllegalArgumentException();
            return dockerImage != null ?
                    Optional.of(new Container(
                            hostName,
                            dockerImage,
                            containerResources,
                            ContainerName.fromHostname(hostName),
                            isRunning ? Container.State.RUNNING : Container.State.EXITED,
                            isRunning ? 1 : 0)) :
                    Optional.empty();
        }).when(dockerOperations).getContainer(any());
    }
    
    private NodeAgentContext createContext(NodeSpec nodeSpec) {
        return new NodeAgentContextImpl.Builder(nodeSpec).build();
    }
}
