// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.os;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.yahoo.component.Version;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.provision.persistence.CuratorDatabaseClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Thread-safe class that manages target OS versions for nodes in this repository.
 *
 * A version target is initially inactive. Activation decision is taken by
 * {@link com.yahoo.vespa.hosted.provision.maintenance.OsUpgradeActivator}.
 *
 * The target OS version for each node type is set through the /nodes/v2/upgrade REST API.
 *
 * @author mpolden
 */
public class OsVersions {

    private static final Duration defaultCacheTtl = Duration.ofMinutes(1);
    private static final Logger log = Logger.getLogger(OsVersions.class.getName());

    private final CuratorDatabaseClient db;
    private final Duration cacheTtl;

    /**
     * Target OS version is read on every request to /nodes/v2/node/[fqdn]. Cache current targets to avoid
     * unnecessary ZK reads. When targets change, some nodes may need to wait for TTL until they see the new target,
     * this is fine.
     */
    private volatile Supplier<Map<NodeType, OsVersion>> currentTargets;

    public OsVersions(CuratorDatabaseClient db) {
        this(db, defaultCacheTtl);
    }

    OsVersions(CuratorDatabaseClient db, Duration cacheTtl) {
        this.db = db;
        this.cacheTtl = cacheTtl;
        createCache();

        // Read and write all versions to make sure they are stored in the latest version of the serialized format
        try (var lock = db.lockOsVersions()) {
            db.writeOsVersions(db.readOsVersions());
        }
    }

    private void createCache() {
        this.currentTargets = Suppliers.memoizeWithExpiration(() -> ImmutableMap.copyOf(db.readOsVersions()),
                                                              cacheTtl.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Returns the current target versions for each node type */
    public Map<NodeType, OsVersion> targets() {
        return currentTargets.get();
    }

    /** Returns the current target version for given node type, if any */
    public Optional<OsVersion> targetFor(NodeType type) {
        return Optional.ofNullable(targets().get(type));
    }

    /** Remove OS target for given node type. Nodes of this type will stop receiving wanted OS version in their
     * node object */
    public void removeTarget(NodeType nodeType) {
        require(nodeType);
        try (Lock lock = db.lockOsVersions()) {
            Map<NodeType, OsVersion> osVersions = db.readOsVersions();
            osVersions.remove(nodeType);
            db.writeOsVersions(osVersions);
            createCache(); // Throw away current cache
            log.info("Cleared OS target version for " + nodeType);
        }
    }

    /** Set the target OS version for nodes of given type */
    public void setTarget(NodeType nodeType, Version newTarget, boolean force) {
        require(nodeType);
        if (newTarget.isEmpty()) {
            throw  new IllegalArgumentException("Invalid target version: " + newTarget.toFullString());
        }
        try (Lock lock = db.lockOsVersions()) {
            Map<NodeType, OsVersion> osVersions = db.readOsVersions();
            Optional<OsVersion> oldTarget = Optional.ofNullable(osVersions.get(nodeType));

            if (oldTarget.filter(v -> v.version().equals(newTarget)).isPresent()) {
                return; // Old target matches new target, nothing to do
            }

            if (!force && oldTarget.filter(v -> v.version().isAfter(newTarget)).isPresent()) {
                throw new IllegalArgumentException("Cannot set target OS version to " + newTarget +
                                                   " without setting 'force', as it's lower than the current version: "
                                                   + oldTarget.get().version());
            }

            osVersions.put(nodeType, new OsVersion(newTarget, false));
            db.writeOsVersions(osVersions);
            createCache(); // Throw away current cache
            log.info("Set OS target version for " + nodeType + " nodes to " + newTarget.toFullString());
        }
    }

    /** Activate or deactivate target for given node type. This is used for resuming or pausing an OS upgrade. */
    public void setActive(NodeType nodeType, boolean active) {
        require(nodeType);
        try (Lock lock = db.lockOsVersions()) {
            var osVersions = db.readOsVersions();
            var currentVersion = osVersions.get(nodeType);
            if (currentVersion == null) return; // No target version set for this type
            if (currentVersion.active() == active) return; // No change

            osVersions.put(nodeType, new OsVersion(currentVersion.version(), active));
            db.writeOsVersions(osVersions);
            createCache(); // Throw away current cache
            log.info((active ? "Activated" : "Deactivated") + " OS target version for " + nodeType + " nodes");
        }
    }

    private static void require(NodeType nodeType) {
        if (!nodeType.isDockerHost()) {
            throw new IllegalArgumentException("Node type '" + nodeType + "' does not support OS upgrades");
        }
    }

}
