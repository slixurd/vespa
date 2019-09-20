// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.application;

import com.google.common.collect.ImmutableMap;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec.UpgradePolicy;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.ApplicationController;
import com.yahoo.vespa.hosted.controller.Instance;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;

/**
 * A list of applications with their instances, which can be filtered in various ways.
 *
 * @author jonmv
 */
public class ApplicationAndInstancesList {

    private final Map<Application, Map<InstanceName, Instance>> map;

    private ApplicationAndInstancesList(Iterable<Map.Entry<Application, Map<InstanceName, Instance>>> applications) {
        this.map = ImmutableMap.copyOf(applications);
    }

    // ----------------------------------- Factories

    public static ApplicationAndInstancesList from(Collection<ApplicationId> ids, ApplicationController applications) {
        return new ApplicationAndInstancesList(ids.stream()
                                                  .map(id -> Map.entry(applications.requireApplication(id),
                                                                       applications.asList(TenantAndApplicationId.from(id))
                                                                                   .stream()
                                                                                   .collect(Collectors.toUnmodifiableMap(instance -> instance.id().instance(),
                                                                                                                         instance -> instance))))
                                                       ::iterator);
    }

    // ----------------------------------- Accessors

    /** Returns the applications in this as an immutable list */
    public Map<Application, Map<InstanceName, Instance>> asMap() { return map; }

    public boolean isEmpty() { return map.isEmpty(); }

    public int size() { return map.size(); }

    // ----------------------------------- Filters

    /** Returns the subset of applications which are upgrading (to any version), not considering block windows. */
    public ApplicationAndInstancesList upgrading() {
        return filteredOn(entry -> entry.getKey().change().platform().isPresent());
    }

    /** Returns the subset of applications which are currently upgrading to the given version */
    public ApplicationAndInstancesList upgradingTo(Version version) {
        return filteredOn(entry -> isUpgradingTo(version, entry.getKey()));
    }

    /** Returns the subset of applications which are not pinned to a certain Vespa version. */
    public ApplicationAndInstancesList unpinned() {
        return filteredOn(entry -> ! entry.getKey().change().isPinned());
    }

    /** Returns the subset of applications which are currently not upgrading to the given version */
    public ApplicationAndInstancesList notUpgradingTo(Version version) {
        return notUpgradingTo(Collections.singletonList(version));
    }

    /** Returns the subset of applications which are currently not upgrading to any of the given versions */
    public ApplicationAndInstancesList notUpgradingTo(Collection<Version> versions) {
        return filteredOn(entry -> versions.stream().noneMatch(version -> isUpgradingTo(version, entry.getKey())));
    }

    /**
     * Returns the subset of applications which are currently not upgrading to the given version,
     * or returns all if no version is specified
     */
    public ApplicationAndInstancesList notUpgradingTo(Optional<Version> version) {
        if (version.isEmpty()) return this;
        return notUpgradingTo(version.get());
    }

    /** Returns the subset of applications which have changes left to deploy; blocked, or deploying */
    public ApplicationAndInstancesList withChanges() {
        return filteredOn(entry -> entry.getKey().change().hasTargets() || entry.getKey().outstandingChange().hasTargets());
    }

    /** Returns the subset of applications which are currently not deploying a change */
    public ApplicationAndInstancesList notDeploying() {
        return filteredOn(entry -> ! entry.getKey().change().hasTargets());
    }

    /** Returns the subset of applications which currently does not have any failing jobs */
    public ApplicationAndInstancesList notFailing() {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .anyMatch(instance -> instance.deploymentJobs().hasFailures()));
    }

    /** Returns the subset of applications which currently have failing jobs */
    public ApplicationAndInstancesList failing() {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .noneMatch(instance -> instance.deploymentJobs().hasFailures()));
    }

    /** Returns the subset of applications which have been failing an upgrade to the given version since the given instant */
    public ApplicationAndInstancesList failingUpgradeToVersionSince(Version version, Instant threshold) {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .anyMatch(instance -> failingUpgradeToVersionSince(instance, version, threshold)));
    }

    /** Returns the subset of applications which have been failing an application change since the given instant */
    public ApplicationAndInstancesList failingApplicationChangeSince(Instant threshold) {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .anyMatch(instance -> failingApplicationChangeSince(instance, threshold)));
    }

    /** Returns the subset of applications which currently does not have any failing jobs on the given version */
    public ApplicationAndInstancesList notFailingOn(Version version) {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .noneMatch(instance -> failingOn(version, instance)));
    }

    /** Returns the subset of applications which have at least one production deployment */
    public ApplicationAndInstancesList hasDeployment() {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .anyMatch(instance -> ! instance.productionDeployments().isEmpty()));
    }

    /** Returns the subset of applications which started failing on the given version */
    public ApplicationAndInstancesList startedFailingOn(Version version) {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .anyMatch(instance -> ! JobList.from(instance).firstFailing().on(version).isEmpty()));
    }

    /** Returns the subset of applications which has the given upgrade policy */
    public ApplicationAndInstancesList with(UpgradePolicy policy) {
        return filteredOn(entry -> entry.getKey().deploymentSpec().upgradePolicy() == policy);
    }

    /** Returns the subset of applications which does not have the given upgrade policy */
    public ApplicationAndInstancesList without(UpgradePolicy policy) {
        return filteredOn(entry -> entry.getKey().deploymentSpec().upgradePolicy() != policy);
    }

    /** Returns the subset of applications which have at least one deployment on a lower version than the given one */
    public ApplicationAndInstancesList onLowerVersionThan(Version version) {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .flatMap(instance -> instance.productionDeployments().values().stream())
                                        .anyMatch(deployment -> deployment.version().isBefore(version)));
    }

    /** Returns the subset of applications which have a project ID */
    public ApplicationAndInstancesList withProjectId() {
        return filteredOn(entry -> entry.getKey().deploymentJobs().projectId().isPresent());
    }

    /** Returns the subset of applications that are allowed to upgrade at the given time */
    public ApplicationAndInstancesList canUpgradeAt(Instant instant) {
        return filteredOn(entry -> entry.getKey().deploymentSpec().canUpgradeAt(instant));
    }

    /** Returns the subset of applications that have at least one assigned rotation */
    public ApplicationAndInstancesList hasRotation() {
        return filteredOn(entry -> entry.getValue().values().stream()
                                        .noneMatch(instance -> instance.rotations().isEmpty()));
    }

    /**
     * Returns the subset of applications that hasn't pinned to an an earlier major version than the given one.
     *
     * @param targetMajorVersion the target major version which applications returned allows upgrading to
     * @param defaultMajorVersion the default major version to assume for applications not specifying one
     */
    public ApplicationAndInstancesList allowMajorVersion(int targetMajorVersion, int defaultMajorVersion) {
        return filteredOn(entry -> targetMajorVersion <= entry.getKey().deploymentSpec().majorVersion()
                                                              .orElse(entry.getKey().majorVersion()
                                                                           .orElse(defaultMajorVersion)));
    }

    /** Returns the first n application in this (or all, if there are less than n). */
    public ApplicationAndInstancesList first(int n) {
        return mapOf(map.entrySet().stream().limit(n));
    }

     // ----------------------------------- Sorting

    /**
     * Returns this list sorted by increasing deployed version.
     * If multiple versions are deployed the oldest is used.
     * Applications without any deployments are ordered first.
     */
    public ApplicationAndInstancesList byIncreasingDeployedVersion() {
        return mapOf(map.entrySet().stream()
                        .sorted(comparing(entry -> entry.getValue().values().stream()
                                                        .flatMap(instance -> instance.productionDeployments().values().stream())
                                                        .map(Deployment::version)
                                                        .min(naturalOrder()).orElse(Version.emptyVersion))));
    }

    // ----------------------------------- Internal helpers

    private static boolean isUpgradingTo(Version version, Application application) {
        return application.change().platform().equals(Optional.of(version));
    }

    private static boolean failingOn(Version version, Instance instance) {
        return ! JobList.from(instance)
                        .failing()
                        .lastCompleted().on(version)
                        .isEmpty();
    }

    private static boolean failingUpgradeToVersionSince(Instance instance, Version version, Instant threshold) {
        return ! JobList.from(instance)
                        .not().failingApplicationChange()
                        .firstFailing().before(threshold)
                        .lastCompleted().on(version)
                        .isEmpty();
    }

    private static boolean failingApplicationChangeSince(Instance instance, Instant threshold) {
        return ! JobList.from(instance)
                        .failingApplicationChange()
                        .firstFailing().before(threshold)
                        .isEmpty();
    }

    /** Convenience converter from a stream to an ApplicationList */
    private static ApplicationAndInstancesList mapOf(Stream<Map.Entry<Application, Map<InstanceName, Instance>>> applications) {
        return new ApplicationAndInstancesList(applications::iterator);
    }

    private ApplicationAndInstancesList filteredOn(Predicate<Map.Entry<Application, Map<InstanceName, Instance>>> filter) {
        return mapOf(map.entrySet().stream().filter(filter));
    }

}
