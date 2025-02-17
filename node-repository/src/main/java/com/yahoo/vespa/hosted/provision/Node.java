// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.Generation;
import com.yahoo.vespa.hosted.provision.node.History;
import com.yahoo.vespa.hosted.provision.node.IP;
import com.yahoo.vespa.hosted.provision.node.Reports;
import com.yahoo.vespa.hosted.provision.node.Status;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A node in the node repository. The identity of a node is given by its id.
 * The classes making up the node model are found in the node package.
 * This (and hence all classes referenced from it) is immutable.
 *
 * @author bratseth
 * @author mpolden
 */
public final class Node {

    private final String hostname;
    private final IP.Config ipConfig;
    private final String id;
    private final Optional<String> parentHostname;
    private final Flavor flavor;
    private final Status status;
    private final State state;
    private final NodeType type;
    private final Reports reports;
    private final Optional<String> modelName;

    /** Record of the last event of each type happening to this node */
    private final History history;

    /** The current allocation of this node, if any */
    private final Optional<Allocation> allocation;

    /** Creates a node in the initial state (reserved) */
    public static Node createDockerNode(Set<String> ipAddresses, String hostname, String parentHostname, NodeResources resources, NodeType type) {
        return new Node("fake-" + hostname, new IP.Config(ipAddresses, Set.of()), hostname, Optional.of(parentHostname), new Flavor(resources), Status.initial(), State.reserved,
                        Optional.empty(), History.empty(), type, new Reports(), Optional.empty());
    }

    /** Creates a node in the initial state (provisioned) */
    public static Node create(String openStackId, IP.Config ipConfig, String hostname, Optional<String> parentHostname, Optional<String> modelName, Flavor flavor, NodeType type) {
        return new Node(openStackId, ipConfig, hostname, parentHostname, flavor, Status.initial(), State.provisioned,
                Optional.empty(), History.empty(), type, new Reports(), modelName);
    }

    /** Creates a node. See also the {@code create} helper methods. */
    public Node(String id, IP.Config ipConfig, String hostname, Optional<String> parentHostname,
                Flavor flavor, Status status, State state, Optional<Allocation> allocation, History history, NodeType type,
                Reports reports, Optional<String> modelName) {
        Objects.requireNonNull(id, "A node must have an ID");
        requireNonEmptyString(hostname, "A node must have a hostname");
        Objects.requireNonNull(ipConfig, "A node must a have an IP config");
        requireNonEmptyString(parentHostname, "A parent host name must be a proper value");
        Objects.requireNonNull(flavor, "A node must have a flavor");
        Objects.requireNonNull(status, "A node must have a status");
        Objects.requireNonNull(state, "A null node state is not permitted");
        Objects.requireNonNull(allocation, "A null node allocation is not permitted");
        Objects.requireNonNull(history, "A null node history is not permitted");
        Objects.requireNonNull(type, "A null node type is not permitted");
        Objects.requireNonNull(reports, "A null reports is not permitted");
        Objects.requireNonNull(modelName, "A null modelName is not permitted");

        if (state == State.active)
            requireNonEmpty(ipConfig.primary(), "An active node must have at least one valid IP address");
        if (parentHostname.isPresent()) {
            if (!ipConfig.pool().asSet().isEmpty()) throw new IllegalArgumentException("A child node cannot have an IP address pool");
            if (modelName.isPresent()) throw new IllegalArgumentException("A child node cannot have model name set");
        }

        this.hostname = hostname;
        this.ipConfig = ipConfig;
        this.parentHostname = parentHostname;
        this.id = id;
        this.flavor = flavor;
        this.status = status;
        this.state = state;
        this.allocation = allocation;
        this.history = history;
        this.type = type;
        this.reports = reports;
        this.modelName = modelName;
    }

    /** Returns the IP addresses of this node */
    // TODO: Remove and make callers access this through ipConfig()
    public Set<String> ipAddresses() { return ipConfig.primary(); }

    /** Returns the IP address pool available on this node. These IP addresses are available for use by containers
     * running on this node */
    // TODO: Remove and make callers access this through ipConfig()
    public IP.Pool ipAddressPool() { return ipConfig.pool(); }

    /** Returns the IP config of this node */
    public IP.Config ipConfig() { return ipConfig; }

    /** Returns the host name of this node */
    public String hostname() { return hostname; }

    /**
     * Unique identifier for this node. Code should not depend on this as its main purpose is to aid human operators in
     * mapping a node to the corresponding cloud instance. No particular format is enforced.
     *
     * Formats used vary between the underlying cloud providers:
     *
     * - OpenStack: UUID
     * - AWS: Instance ID
     * - Docker containers: fake-[hostname]
     */
    public String id() { return id; }

    /** Returns the parent hostname for this node if this node is a docker container or a VM (i.e. it has a parent host). Otherwise, empty **/
    public Optional<String> parentHostname() { return parentHostname; }

    /** Returns the flavor of this node */
    public Flavor flavor() { return flavor; }

    /** Returns the known information about the node's ephemeral status */
    public Status status() { return status; }

    /** Returns the current state of this node (in the node state machine) */
    public State state() { return state; }

    /** Returns the type of this node */
    public NodeType type() { return type; }

    /** Returns the current allocation of this, if any */
    public Optional<Allocation> allocation() { return allocation; }

    /** Returns the current allocation when it must exist, or throw exception there is not allocation. */
    private Allocation requireAllocation(String message) {
        final Optional<Allocation> allocation = this.allocation;
        if ( ! allocation.isPresent())
            throw new IllegalStateException(message + " for  " + hostname() + ": The node is unallocated");

        return allocation.get();
    }

    /** Returns a history of the last events happening to this node */
    public History history() { return history; }

    /** Returns all the reports on this node. */
    public Reports reports() { return reports; }

    /** Returns the hardware model of this node */
    public Optional<String> modelName() { return modelName; }

    /**
     * Returns a copy of this node with wantToRetire set to the given value and updated history.
     * If given wantToRetire is equal to the current, the method is no-op.
     */
    public Node withWantToRetire(boolean wantToRetire, Agent agent, Instant at) {
        if (wantToRetire == status.wantToRetire()) return this;
        return with(status.withWantToRetire(wantToRetire))
                .with(history.with(new History.Event(History.Event.Type.wantToRetire, Agent.operator, at)));
    }

    /**
     * Returns a copy of this node which is retired.
     * If the node was already retired it is returned as-is.
     */
    public Node retire(Agent agent, Instant retiredAt) {
        Allocation allocation = requireAllocation("Cannot retire");
        if (allocation.membership().retired()) return this;
        return with(allocation.retire())
                .with(history.with(new History.Event(History.Event.Type.retired, agent, retiredAt)));
    }

    /** Returns a copy of this node which is retired */
    public Node retire(Instant retiredAt) {
        if (status.wantToRetire())
            return retire(Agent.system, retiredAt);
        else
            return retire(Agent.application, retiredAt);
    }

    /** Returns a copy of this node which is not retired */
    public Node unretire() {
        return with(requireAllocation("Cannot unretire").unretire());
    }

    /** Returns a copy of this with the restart generation set to generation */
    public Node withRestart(Generation generation) {
        Allocation allocation = requireAllocation("Cannot set restart generation");
        return with(allocation.withRestart(generation));
    }

    /** Returns a node with the status assigned to the given value */
    public Node with(Status status) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, modelName);
    }

    /** Returns a node with the type assigned to the given value */
    public Node with(NodeType type) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, modelName);
    }

    /** Returns a node with the flavor assigned to the given value */
    public Node with(Flavor flavor) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, modelName);
    }

    /** Returns a copy of this with the reboot generation set to generation */
    public Node withReboot(Generation generation) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status.withReboot(generation), state, allocation, history, type, reports, modelName);
    }

    /** Returns a copy of this with the openStackId set */
    public Node withOpenStackId(String openStackId) {
        return new Node(openStackId, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, modelName);
    }

    /** Returns a copy of this with model name set to given value */
    public Node withModelName(String modelName) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, Optional.of(modelName));
    }

    /** Returns a copy of this with model name cleared */
    public Node withoutModelName() {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, Optional.empty());
    }

    /** Returns a copy of this with a history record saying it was detected to be down at this instant */
    public Node downAt(Instant instant) {
        return with(history.with(new History.Event(History.Event.Type.down, Agent.system, instant)));
    }

    /** Returns a copy of this with any history record saying it has been detected down removed */
    public Node up() {
        return with(history.without(History.Event.Type.down));
    }

    /** Returns a copy of this with allocation set as specified. <code>node.state</code> is *not* changed. */
    public Node allocate(ApplicationId owner, ClusterMembership membership, Instant at) {
        return this.with(new Allocation(owner, membership, new Generation(0, 0), false))
                .with(history.with(new History.Event(History.Event.Type.reserved, Agent.application, at)));
    }

    /**
     * Returns a copy of this node with the allocation assigned to the given allocation.
     * Do not use this to allocate a node.
     */
    public Node with(Allocation allocation) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state,
                        Optional.of(allocation), history, type, reports, modelName);
    }

    /** Returns a new Node without an allocation. */
    public Node withoutAllocation() {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state,
                        Optional.empty(), history, type, reports, modelName);
    }


    /** Returns a copy of this node with IP config set to the given value. */
    public Node with(IP.Config ipConfig) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state,
                        allocation, history, type, reports, modelName);
    }

    /** Returns a copy of this node with the parent hostname assigned to the given value. */
    public Node withParentHostname(String parentHostname) {
        return new Node(id, ipConfig, hostname, Optional.of(parentHostname), flavor, status, state,
                        allocation, history, type, reports, modelName);
    }

    /** Returns a copy of this node with the current reboot generation set to the given number at the given instant */
    public Node withCurrentRebootGeneration(long generation, Instant instant) {
        Status newStatus = status().withReboot(status().reboot().withCurrent(generation));
        History newHistory = history();
        if (generation > status().reboot().current())
            newHistory = history.with(new History.Event(History.Event.Type.rebooted, Agent.system, instant));
        return this.with(newStatus).with(newHistory);
    }

    /** Returns a copy of this node with the given history. */
    public Node with(History history) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, modelName);
    }

    public Node with(Reports reports) {
        return new Node(id, ipConfig, hostname, parentHostname, flavor, status, state, allocation, history, type, reports, modelName);
    }

    private static void requireNonEmptyString(Optional<String> value, String message) {
        Objects.requireNonNull(value, message);
        value.ifPresent(v -> requireNonEmptyString(v, message));
    }

    private static void requireNonEmptyString(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.trim().isEmpty())
            throw new IllegalArgumentException(message + ", but was '" + value + "'");
    }

    private static void requireNonEmpty(Set<String> values, String message) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return hostname.equals(node.hostname);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostname);
    }

    @Override
    public String toString() {
        return state + " node " +
               hostname +
               (allocation.map(allocation1 -> " " + allocation1).orElse("")) +
               (parentHostname.map(parent -> " [on: " + parent + "]").orElse(""));
    }

    public enum State {

        /** This node has been requested (from OpenStack) but is not yet ready for use */
        provisioned,

        /** This node is free and ready for use */
        ready,

        /** This node has been reserved by an application but is not yet used by it */
        reserved,

        /** This node is in active use by an application */
        active,

        /** This node has been used by an application, is still allocated to it and retains the data needed for its allocated role */
        inactive,

        /** This node is not allocated to an application but may contain data which must be cleaned before it is ready */
        dirty,

        /** This node has failed and must be repaired or removed. The node retains any allocation data for diagnosis. */
        failed,

        /**
         * This node should not currently be used.
         * This state follows the same rules as failed except that it will never be automatically moved out of
         * this state.
         */
        parked;

        /** Returns whether this is a state where the node is assigned to an application */
        public boolean isAllocated() {
            return this == reserved || this == active || this == inactive || this == failed || this == parked;
        }
    }

}
