// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.NodeResources;
import com.yahoo.vespa.hosted.provision.Node;

import java.util.Optional;

import static com.yahoo.vespa.hosted.provision.provisioning.NodePrioritizer.ALLOCATABLE_HOST_STATES;

/**
 * A node with additional information required to prioritize it for allocation.
 *
 * @author smorgrav
 */
class PrioritizableNode implements Comparable<PrioritizableNode> {

    // TODO: Make immutable
    Node node;

    /** The free capacity, including retired allocations */
    final NodeResources freeParentCapacity;

    /** The parent host (docker or hypervisor) */
    final Optional<Node> parent;

    /** True if the node is allocated to a host that should be dedicated as a spare */
    final boolean violatesSpares;

    /** True if this is a node that has been retired earlier in the allocation process */
    final boolean isSurplusNode;

    /** This node does not exist in the node repository yet */
    final boolean isNewNode;

    PrioritizableNode(Node node, NodeResources freeParentCapacity, Optional<Node> parent, boolean violatesSpares, boolean isSurplusNode, boolean isNewNode) {
        this.node = node;
        this.freeParentCapacity = freeParentCapacity;
        this.parent = parent;
        this.violatesSpares = violatesSpares;
        this.isSurplusNode = isSurplusNode;
        this.isNewNode = isNewNode;
    }

    /**
     * Compares two prioritizable nodes
     *
     * @return negative if first priority is higher than second node
     */
    @Override
    public int compareTo(PrioritizableNode other) {
        // First always pick nodes without violation above nodes with violations
        if (!this.violatesSpares && other.violatesSpares) return -1;
        if (!other.violatesSpares && this.violatesSpares) return 1;

        // Choose active nodes
        if (this.node.state() == Node.State.active && other.node.state() != Node.State.active) return -1;
        if (other.node.state() == Node.State.active && this.node.state() != Node.State.active) return 1;

        // Choose active node that is not retired first (surplus is active but retired)
        if (!this.isSurplusNode && other.isSurplusNode) return -1;
        if (!other.isSurplusNode && this.isSurplusNode) return 1;

        // Choose inactive nodes
        if (this.node.state() == Node.State.inactive && other.node.state() != Node.State.inactive) return -1;
        if (other.node.state() == Node.State.inactive && this.node.state() != Node.State.inactive) return 1;

        // Choose reserved nodes from a previous allocation attempt (the exist in node repo)
        if (isInNodeRepoAndReserved(this) && !isInNodeRepoAndReserved(other)) return -1;
        if (isInNodeRepoAndReserved(other) && !isInNodeRepoAndReserved(this)) return 1;

        // Choose ready nodes
        if (this.node.state() == Node.State.ready && other.node.state() != Node.State.ready) return -1;
        if (other.node.state() == Node.State.ready && this.node.state() != Node.State.ready) return 1;

        if (this.node.state() != other.node.state())
            throw new IllegalStateException("Nodes " + this.node + " and " + other.node + " have different states");

        // Choose nodes where host is in more desirable state
        int thisHostStatePri = this.parent.map(host -> ALLOCATABLE_HOST_STATES.indexOf(host.state())).orElse(-2);
        int otherHostStatePri = other.parent.map(host -> ALLOCATABLE_HOST_STATES.indexOf(host.state())).orElse(-2);
        if (thisHostStatePri != otherHostStatePri) return otherHostStatePri - thisHostStatePri;

        // Choose the node with parent node with the least capacity (TODO parameterize this as this is pretty much the core of the algorithm)
        int freeCapacity = NodeResourceComparator.defaultOrder().compare(this.freeParentCapacity, other.freeParentCapacity);
        if (freeCapacity != 0) return freeCapacity;

        // Choose cheapest node
        if (this.node.flavor().cost() < other.node.flavor().cost()) return -1;
        if (other.node.flavor().cost() < this.node.flavor().cost()) return 1;

        // All else equal choose hostname alphabetically
        return this.node.hostname().compareTo(other.node.hostname());
    }

    private static boolean isInNodeRepoAndReserved(PrioritizableNode nodePri) {
        if (nodePri.isNewNode) return false;
        return nodePri.node.state().equals(Node.State.reserved);
    }

    static class Builder {
        public final Node node;
        private NodeResources freeParentCapacity = new NodeResources(0, 0, 0, 0);
        private Optional<Node> parent = Optional.empty();
        private boolean violatesSpares;
        private boolean isSurplusNode;
        private boolean isNewNode;

        Builder(Node node) {
            this.node = node;
        }

        Builder withFreeParentCapacity(NodeResources freeParentCapacity) {
            this.freeParentCapacity = freeParentCapacity;
            return this;
        }

        Builder withParent(Node parent) {
            this.parent = Optional.of(parent);
            return this;
        }

        Builder withViolatesSpares(boolean violatesSpares) {
            this.violatesSpares = violatesSpares;
            return this;
        }

        Builder withSurplusNode(boolean surplusNode) {
            isSurplusNode = surplusNode;
            return this;
        }

        Builder withNewNode(boolean newNode) {
            isNewNode = newNode;
            return this;
        }
        
        PrioritizableNode build() {
            return new PrioritizableNode(node, freeParentCapacity, parent, violatesSpares, isSurplusNode, isNewNode);
        }
    }

}
