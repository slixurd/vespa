// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision;

import com.google.common.collect.ImmutableList;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.collectingAndThen;

/**
 * A filterable node list
 *
 * @author bratseth
 * @author mpolden
 */
public class NodeList implements Iterable<Node> {

    private final List<Node> nodes;

    public NodeList(List<Node> nodes) {
        this(nodes, true);
    }

    private NodeList(List<Node> nodes, boolean copy) {
        this.nodes = copy ? ImmutableList.copyOf(nodes) : Collections.unmodifiableList(nodes);
    }

    /** Returns the subset of nodes which are retired */
    public NodeList retired() {
        return filter(node -> node.allocation().get().membership().retired());
    }

    /** Returns the subset of nodes which are not retired */
    public NodeList nonretired() {
        return filter(node -> ! node.allocation().get().membership().retired());
    }

    /** Returns the subset of nodes having exactly the given resources */
    public NodeList resources(NodeResources resources) { return filter(node -> node.flavor().resources().equals(resources)); }

    /** Returns the subset of nodes not having exactly the given resources */
    public NodeList notResources(NodeResources resources) { return filter(node ->  ! node.flavor().resources().equals(resources)); }

    /** Returns the subset of nodes of the given flavor */
    public NodeList flavor(String flavor) {
        return filter(node -> node.flavor().name().equals(flavor));
    }

    /** Returns the subset of nodes which does not have the given flavor */
    public NodeList notFlavor(String flavor) {
        return filter(node ->  ! node.flavor().name().equals(flavor));
    }

    /** Returns the subset of nodes assigned to the given cluster type */
    public NodeList type(ClusterSpec.Type type) {
        return filter(node -> node.allocation().isPresent() && node.allocation().get().membership().cluster().type().equals(type));
    }

    /** Returns the subset of nodes that are currently changing their Vespa version */
    public NodeList changingVersion() {
        return filter(node -> node.status().vespaVersion().isPresent() &&
                              node.allocation().isPresent() &&
                              !node.status().vespaVersion().get().equals(node.allocation().get().membership().cluster().vespaVersion()));
    }

    /** Returns the subset of nodes assigned to the given cluster */
    public NodeList cluster(ClusterSpec.Id cluster) {
        return filter(node -> node.allocation().isPresent() && node.allocation().get().membership().cluster().id().equals(cluster));
    }

    /** Returns the subset of nodes owned by the given application */
    public NodeList owner(ApplicationId application) {
        return filter(node -> node.allocation().map(a -> a.owner().equals(application)).orElse(false));
    }

    /** Returns the subset of nodes matching the given node type(s) */
    public NodeList nodeType(NodeType first, NodeType... rest) {
        EnumSet<NodeType> nodeTypes = EnumSet.of(first, rest);
        return filter(node -> nodeTypes.contains(node.type()));
    }

    /** Returns the subset of nodes that are parents */
    public NodeList parents() {
        return filter(n -> n.parentHostname().isEmpty());
    }

    /** Returns the child nodes of the given parent node */
    public NodeList childrenOf(String hostname) {
        return filter(n -> n.parentHostname().map(hostname::equals).orElse(false));
    }

    public NodeList childrenOf(Node parent) {
        return childrenOf(parent.hostname());
    }

    /** Returns the subset of nodes that are in a given state(s) */
    public NodeList state(Node.State first, Node.State... rest) {
        EnumSet<Node.State> nodeStates = EnumSet.of(first, rest);
        return filter(node -> nodeStates.contains(node.state()));
    }

    /** Returns the parent nodes of the given child nodes */
    public NodeList parentsOf(Collection<Node> children) {
        return children.stream()
                       .map(this::parentOf)
                       .filter(Optional::isPresent)
                       .flatMap(Optional::stream)
                       .collect(collectingAndThen(Collectors.toList(), NodeList::wrap));
    }

    /** Returns the parent node of the given child node */
    public Optional<Node> parentOf(Node child) {
        return child.parentHostname()
                .flatMap(parentHostname -> nodes.stream()
                        .filter(node -> node.hostname().equals(parentHostname))
                        .findFirst());
    }

    public int size() { return nodes.size(); }

    /** Returns the immutable list of nodes in this */
    public List<Node> asList() { return nodes; }

    public NodeList filter(Predicate<Node> predicate) {
        return nodes.stream().filter(predicate).collect(collectingAndThen(Collectors.toList(), NodeList::wrap));
    }

    @Override
    public Iterator<Node> iterator() {
        return nodes.iterator();
    }

    private static NodeList wrap(List<Node> nodes) {
        return new NodeList(nodes, false);
    }
}
