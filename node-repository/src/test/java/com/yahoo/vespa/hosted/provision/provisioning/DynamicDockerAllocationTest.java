// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.google.common.collect.ImmutableSet;
import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.OutOfCapacityException;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.config.provisioning.FlavorsConfig;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.curator.transaction.CuratorTransaction;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.IP;
import org.junit.Ignore;
import org.junit.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * @author mortent
 */
public class DynamicDockerAllocationTest {

    /**
     * Test relocation of nodes from spare hosts.
     * <p>
     * Setup 4 docker hosts and allocate one container on each (from two different applications)
     * getSpareCapacityProd() spares.
     * <p>
     * Check that it relocates containers away from the getSpareCapacityProd() spares
     * <p>
     * Initial allocation of app 1 and 2 --> final allocation (example using 2 spares):
     * <p>
     * |    |    |    |    |        |    |    |    |    |
     * |    |    |    |    |   -->  | 2a | 2b |    |    |
     * | 1a | 1b | 2a | 2b |        | 1a | 1b |    |    |
     */
    @Test
    public void relocate_nodes_from_spare_hosts() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(4, "host-small", NodeType.host, 32);
        deployZoneApp(tester);
        List<Node> dockerHosts = tester.nodeRepository().getNodes(NodeType.host, Node.State.active);
        NodeResources flavor = new NodeResources(1, 4, 10, 1);

        // Application 1
        ApplicationId application1 = makeApplicationId("t1", "a1");
        ClusterSpec clusterSpec1 = clusterSpec("myContent.t1.a1");
        addAndAssignNode(application1, "1a", dockerHosts.get(0).hostname(), clusterSpec1, flavor, 0, tester);
        addAndAssignNode(application1, "1b", dockerHosts.get(1).hostname(), clusterSpec1, flavor, 1, tester);

        // Application 2
        ApplicationId application2 = makeApplicationId("t2", "a2");
        ClusterSpec clusterSpec2 = clusterSpec("myContent.t2.a2");
        addAndAssignNode(application2, "2a", dockerHosts.get(2).hostname(), clusterSpec2, flavor, 3, tester);
        addAndAssignNode(application2, "2b", dockerHosts.get(3).hostname(), clusterSpec2, flavor, 4, tester);

        // Redeploy both applications (to be agnostic on which hosts are picked as spares)
        deployApp(application1, clusterSpec1, flavor, tester, 2);
        deployApp(application2, clusterSpec2, flavor, tester, 2);

        // Assert that we have two spare nodes (two hosts that are don't have allocations)
        Set<String> hostsWithChildren = new HashSet<>();
        for (Node node : tester.nodeRepository().getNodes(NodeType.tenant, Node.State.active)) {
            if (!isInactiveOrRetired(node)) {
                hostsWithChildren.add(node.parentHostname().get());
            }
        }
        assertEquals(4 - tester.provisioner().getSpareCapacityProd(), hostsWithChildren.size());

    }

    /**
     * Test an allocation workflow:
     * <p>
     * 5 Hosts of capacity 3 (2 spares)
     * - Allocate app with 3 nodes
     * - Allocate app with 2 nodes
     * - Fail host and check redistribution
     */
    @Test
    public void relocate_failed_nodes() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(5, "host-small", NodeType.host, 32);
        deployZoneApp(tester);
        List<Node> dockerHosts = tester.nodeRepository().getNodes(NodeType.host, Node.State.active);
        NodeResources flavor = new NodeResources(1, 4, 10, 0.3);

        // Application 1
        ApplicationId application1 = makeApplicationId("t1", "a1");
        ClusterSpec clusterSpec1 = clusterSpec("myContent.t1.a1");
        deployApp(application1, clusterSpec1, flavor, tester, 3);

        // Application 2
        ApplicationId application2 = makeApplicationId("t2", "a2");
        ClusterSpec clusterSpec2 = clusterSpec("myContent.t2.a2");
        deployApp(application2, clusterSpec2, flavor, tester, 2);

        // Application 3
        ApplicationId application3 = makeApplicationId("t3", "a3");
        ClusterSpec clusterSpec3 = clusterSpec("myContent.t3.a3");
        deployApp(application3, clusterSpec3, flavor, tester, 2);

        // App 2 and 3 should have been allocated to the same nodes - fail one of the parent hosts from there
        String parent = tester.nodeRepository().getNodes(application2).stream().findAny().get().parentHostname().get();
        tester.nodeRepository().failRecursively(parent, Agent.system, "Testing");

        // Redeploy all applications
        deployApp(application1, clusterSpec1, flavor, tester, 3);
        deployApp(application2, clusterSpec2, flavor, tester, 2);
        deployApp(application3, clusterSpec3, flavor, tester, 2);

        Map<Integer, Integer> numberOfChildrenStat = new HashMap<>();
        for (Node node : dockerHosts) {
            int nofChildren = tester.nodeRepository().list().childrenOf(node).size();
            if (!numberOfChildrenStat.containsKey(nofChildren)) {
                numberOfChildrenStat.put(nofChildren, 0);
            }
            numberOfChildrenStat.put(nofChildren, numberOfChildrenStat.get(nofChildren) + 1);
        }

        assertEquals(3, numberOfChildrenStat.get(3).intValue());
        assertEquals(1, numberOfChildrenStat.get(0).intValue());
        assertEquals(1, numberOfChildrenStat.get(1).intValue());
    }

    /**
     * Test redeployment of nodes that violates spare headroom - but without alternatives
     * <p>
     * Setup 2 docker hosts and allocate one app with a container on each. 2 spares
     * <p>
     * Initial allocation of app 1 --> final allocation:
     * <p>
     * |    |    |        |    |    |
     * |    |    |   -->  |    |    |
     * | 1a | 1b |        | 1a | 1b |
     */
    @Test
    public void do_not_relocate_nodes_from_spare_if_no_where_to_relocate_them() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(2, "host-small", NodeType.host, 32);
        deployZoneApp(tester);
        List<Node> dockerHosts = tester.nodeRepository().getNodes(NodeType.host, Node.State.active);
        NodeResources flavor = new NodeResources(1, 4, 10, 1);

        // Application 1
        ApplicationId application1 = makeApplicationId("t1", "a1");
        ClusterSpec clusterSpec1 = clusterSpec("myContent.t1.a1");
        addAndAssignNode(application1, "1a", dockerHosts.get(0).hostname(), clusterSpec1, flavor, 0, tester);
        addAndAssignNode(application1, "1b", dockerHosts.get(1).hostname(), clusterSpec1, flavor, 1, tester);

        // Redeploy both applications (to be agnostic on which hosts are picked as spares)
        deployApp(application1, clusterSpec1, flavor, tester, 2);

        // Assert that we have two spare nodes (two hosts that are don't have allocations)
        Set<String> hostsWithChildren = new HashSet<>();
        for (Node node : tester.nodeRepository().getNodes(NodeType.tenant, Node.State.active)) {
            if (!isInactiveOrRetired(node)) {
                hostsWithChildren.add(node.parentHostname().get());
            }
        }
        assertEquals(2, hostsWithChildren.size());
    }

    @Test(expected = OutOfCapacityException.class)
    public void multiple_groups_are_on_separate_parent_hosts() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(5, "host-small", NodeType.host, 32);
        deployZoneApp(tester);

        //Deploy an application having 6 nodes (3 nodes in 2 groups). We only have 5 docker hosts available
        ApplicationId application1 = tester.makeApplicationId();
        tester.prepare(application1, clusterSpec("myContent.t1.a1"), 6, 2, new NodeResources(1, 4, 10, 1));

        fail("Two groups have been allocated to the same parent host");
    }

    @Ignore // TODO: Re-enable if we reintroduce spare capacity requirement
    @Test
    public void spare_capacity_used_only_when_replacement() {
        // Use spare capacity only when replacement (i.e one node is failed)
        // Test should allocate as much capacity as possible, verify that it is not possible to allocate one more unit
        // Verify that there is still capacity (available spare)
        // Fail one node and redeploy, Verify that one less node is empty.
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();

        // Setup test
        ApplicationId application1 = tester.makeApplicationId();
        tester.makeReadyNodes(5, "host-small", NodeType.host, 32);
        deployZoneApp(tester);
        NodeResources flavor = new NodeResources(1, 4, 10, 1);

        // Deploy initial state (can max deploy 3 nodes due to redundancy requirements)
        ClusterSpec clusterSpec = clusterSpec("myContent.t1.a1");
        List<HostSpec> hosts = tester.prepare(application1, clusterSpec, 3, 1, flavor);
        tester.activate(application1, ImmutableSet.copyOf(hosts));

        List<Node> initialSpareCapacity = findSpareCapacity(tester);
        assertThat(initialSpareCapacity.size(), is(2));

        try {
            hosts = tester.prepare(application1, clusterSpec, 4, 1, flavor);
            fail("Was able to deploy with 4 nodes, should not be able to use spare capacity");
        } catch (OutOfCapacityException ignored) { }

        tester.fail(hosts.get(0));
        hosts = tester.prepare(application1, clusterSpec, 3, 1, flavor);
        tester.activate(application1, ImmutableSet.copyOf(hosts));

        List<Node> finalSpareCapacity = findSpareCapacity(tester);

        assertThat(finalSpareCapacity.size(), is(1));
    }

    @Test
    public void non_prod_zones_do_not_have_spares() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.perf, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(3, "host-small", NodeType.host, 32);
        deployZoneApp(tester);
        ApplicationId application1 = tester.makeApplicationId();
        List<HostSpec> hosts = tester.prepare(application1, clusterSpec("myContent.t1.a1"), 3, 1, new NodeResources(1, 4, 10, 1));
        tester.activate(application1, ImmutableSet.copyOf(hosts));

        List<Node> initialSpareCapacity = findSpareCapacity(tester);
        assertEquals(0, initialSpareCapacity.size());
    }

    @Test
    public void cd_uses_slow_disk_nodes_for_docker_hosts() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(SystemName.cd, Environment.test, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(4, new Flavor(new NodeResources(1, 8, 12, 1, NodeResources.DiskSpeed.slow)), NodeType.host, 10, true);
        deployZoneApp(tester);
        ApplicationId application1 = tester.makeApplicationId();
        List<HostSpec> hosts = tester.prepare(application1, clusterSpec("myContent.t1.a1"), 3, 1, new NodeResources(1, 4, 10, 1));
        tester.activate(application1, ImmutableSet.copyOf(hosts));
    }

    @Test(expected = OutOfCapacityException.class)
    public void allocation_should_fail_when_host_is_not_in_allocatable_state() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeProvisionedNodes(3, "host-small", NodeType.host, 32).forEach(node ->
                tester.nodeRepository().fail(node.hostname(), Agent.system, getClass().getSimpleName()));

        ApplicationId application = tester.makeApplicationId();
        tester.prepare(application, clusterSpec("myContent.t2.a2"), 2, 1, new NodeResources(1, 4, 10, 1));
    }

    @Test
    public void provision_dual_stack_containers() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(2, "host-large", NodeType.host, 10, true);
        deployZoneApp(tester);

        ApplicationId application = tester.makeApplicationId();
        List<HostSpec> hosts = tester.prepare(application, clusterSpec("myContent.t1.a1"), 2, 1, new NodeResources(1, 4, 10, 1));
        tester.activate(application, hosts);

        List<Node> activeNodes = tester.nodeRepository().getNodes(application);
        assertEquals(ImmutableSet.of("127.0.127.13", "::13"), activeNodes.get(0).ipAddresses());
        assertEquals(ImmutableSet.of("127.0.127.2", "::2"), activeNodes.get(1).ipAddresses());
    }

    @Test
    public void provisioning_fast_disk_speed_do_not_get_slow_nodes() {
        provisionFastAndSlowThenDeploy(NodeResources.DiskSpeed.fast, true);
    }

    @Test
    public void provisioning_slow_disk_speed_do_not_get_fast_nodes() {
        provisionFastAndSlowThenDeploy(NodeResources.DiskSpeed.slow, true);
    }

    @Test
    public void provisioning_any_disk_speed_gets_slow_and_fast_nodes() {
        provisionFastAndSlowThenDeploy(NodeResources.DiskSpeed.any, false);
    }

    @Test
    public void slow_disk_nodes_are_preferentially_allocated() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(2, new Flavor(new NodeResources(1, 8, 12, 1, NodeResources.DiskSpeed.fast)), NodeType.host, 10, true);
        tester.makeReadyNodes(2, new Flavor(new NodeResources(1, 8, 12, 1, NodeResources.DiskSpeed.slow)), NodeType.host, 10, true);
        deployZoneApp(tester);

        ApplicationId application = tester.makeApplicationId();
        ClusterSpec cluster = ClusterSpec.request(ClusterSpec.Type.container, ClusterSpec.Id.from("test"), Version.fromString("1"), false);
        NodeResources resources = new NodeResources(1, 4, 10, 1, NodeResources.DiskSpeed.any);

        List<HostSpec> hosts = tester.prepare(application, cluster, 2, 1, resources);
        assertEquals(2, hosts.size());
        assertEquals(NodeResources.DiskSpeed.slow, hosts.get(0).flavor().get().resources().diskSpeed());
        assertEquals(NodeResources.DiskSpeed.slow, hosts.get(1).flavor().get().resources().diskSpeed());
        tester.activate(application, hosts);
    }

    private void provisionFastAndSlowThenDeploy(NodeResources.DiskSpeed requestDiskSpeed, boolean expectOutOfCapacity) {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(2, new Flavor(new NodeResources(1, 8, 12, 1, NodeResources.DiskSpeed.fast)), NodeType.host, 10, true);
        tester.makeReadyNodes(2, new Flavor(new NodeResources(1, 8, 12, 1, NodeResources.DiskSpeed.slow)), NodeType.host, 10, true);
        deployZoneApp(tester);

        ApplicationId application = tester.makeApplicationId();
        ClusterSpec cluster = ClusterSpec.request(ClusterSpec.Type.container, ClusterSpec.Id.from("test"), Version.fromString("1"), false);
        NodeResources resources = new NodeResources(1, 4, 10, 1, requestDiskSpeed);

        try {
            List<HostSpec> hosts = tester.prepare(application, cluster, 4, 1, resources);
            if (expectOutOfCapacity) fail("Expected out of capacity");
            assertEquals(4, hosts.size());
            tester.activate(application, hosts);
        }
        catch (OutOfCapacityException e) {
            if ( ! expectOutOfCapacity) throw e;
        }
    }

    @Test
    public void nodeResourcesAreRelaxedInDev() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.dev, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(2, new Flavor(new NodeResources(1, 8, 12, 1, NodeResources.DiskSpeed.fast)), NodeType.host, 10, true);
        tester.makeReadyNodes(2, new Flavor(new NodeResources(1, 8, 12, 1, NodeResources.DiskSpeed.slow)), NodeType.host, 10, true);
        deployZoneApp(tester);

        ApplicationId application = tester.makeApplicationId();
        ClusterSpec cluster = ClusterSpec.request(ClusterSpec.Type.container, ClusterSpec.Id.from("test"), Version.fromString("1"), false);
        NodeResources resources = new NodeResources(1, 4, 10, 1, NodeResources.DiskSpeed.fast);

        List<HostSpec> hosts = tester.prepare(application, cluster, 4, 1, resources);
        assertEquals(1, hosts.size());
        tester.activate(application, hosts);
        assertEquals(0.1, hosts.get(0).flavor().get().resources().vcpu(), 0.000001);
        assertEquals("Slow nodes are allowed in dev and preferred because they are cheaper",
                     NodeResources.DiskSpeed.slow, hosts.get(0).flavor().get().resources().diskSpeed());
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testSwitchingFromLegacyFlavorSyntaxToResourcesDoesNotCauseReallocation() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).flavorsConfig(flavorsConfig()).build();
        tester.makeReadyNodes(2, new Flavor(new NodeResources(5, 20, 140, 3)), NodeType.host, 10, true);
        deployZoneApp(tester);

        ApplicationId application = tester.makeApplicationId();
        ClusterSpec cluster = ClusterSpec.request(ClusterSpec.Type.container, ClusterSpec.Id.from("test"), Version.fromString("1"), false);

        List<HostSpec> hosts1 = tester.prepare(application, cluster, Capacity.fromNodeCount(2, Optional.of("d-2-8-50"), false, true), 1);
        tester.activate(application, hosts1);

        NodeResources resources = new NodeResources(1.5, 8, 50, 0.3);
        List<HostSpec> hosts2 = tester.prepare(application, cluster, Capacity.fromCount(2, resources), 1);
        tester.activate(application, hosts2);

        assertEquals(hosts1, hosts2);
    }

    private ApplicationId makeApplicationId(String tenant, String appName) {
        return ApplicationId.from(tenant, appName, "default");
    }

    private void deployApp(ApplicationId id, ClusterSpec spec, NodeResources flavor, ProvisioningTester tester, int nodeCount) {
        List<HostSpec> hostSpec = tester.prepare(id, spec, nodeCount, 1, flavor);
        tester.activate(id, new HashSet<>(hostSpec));
    }

    private void addAndAssignNode(ApplicationId id, String hostname, String parentHostname, ClusterSpec clusterSpec, NodeResources flavor, int index, ProvisioningTester tester) {
        Node node1a = Node.create("open1", new IP.Config(Set.of("127.0.233." + index), Set.of()), hostname, Optional.of(parentHostname), Optional.empty(), new Flavor(flavor), NodeType.tenant);
        ClusterMembership clusterMembership1 = ClusterMembership.from(
                clusterSpec.with(Optional.of(ClusterSpec.Group.from(0))), index); // Need to add group here so that group is serialized in node allocation
        Node node1aAllocation = node1a.allocate(id, clusterMembership1, Instant.now());

        tester.nodeRepository().addNodes(Collections.singletonList(node1aAllocation));
        NestedTransaction transaction = new NestedTransaction().add(new CuratorTransaction(tester.getCurator()));
        tester.nodeRepository().activate(Collections.singletonList(node1aAllocation), transaction);
        transaction.commit();
    }

    private List<Node> findSpareCapacity(ProvisioningTester tester) {
        List<Node> nodes = tester.nodeRepository().getNodes(Node.State.values());
        NodeList nl = new NodeList(nodes);
        return nodes.stream()
                    .filter(n -> n.type() == NodeType.host)
                    .filter(n -> nl.childrenOf(n).size() == 0) // Nodes without children
                    .collect(Collectors.toList());
    }

    private FlavorsConfig flavorsConfig() {
        FlavorConfigBuilder b = new FlavorConfigBuilder();
        b.addFlavor("host-large", 6., 24., 80, 6, Flavor.Type.BARE_METAL);
        b.addFlavor("host-small", 3., 12., 40, 3, Flavor.Type.BARE_METAL);
        return b.build();
    }

    private void deployZoneApp(ProvisioningTester tester) {
        ApplicationId applicationId = tester.makeApplicationId();
        List<HostSpec> list = tester.prepare(applicationId,
                ClusterSpec.request(ClusterSpec.Type.container,
                                    ClusterSpec.Id.from("node-admin"),
                                    Version.fromString("6.42"),
                                    false),
                Capacity.fromRequiredNodeType(NodeType.host),
                1);
        tester.activate(applicationId, ImmutableSet.copyOf(list));
    }

    private boolean isInactiveOrRetired(Node node) {
        boolean isInactive = node.state().equals(Node.State.inactive);
        boolean isRetired = false;
        if (node.allocation().isPresent()) {
            isRetired = node.allocation().get().membership().retired();
        }

        return isInactive || isRetired;
    }

    private ClusterSpec clusterSpec(String clusterId) {
        return ClusterSpec.request(ClusterSpec.Type.content, ClusterSpec.Id.from(clusterId), Version.fromString("6.42"), false);
    }
}
