// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.integration;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.AbstractComponent;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.UpgradePolicy;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.config.provision.zone.ZoneFilter;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.athenz.api.AthenzService;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneRegistry;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author mpolden
 */
public class ZoneRegistryMock extends AbstractComponent implements ZoneRegistry {

    private final Map<ZoneId, Duration> deploymentTimeToLive = new HashMap<>();
    private final Map<Environment, RegionName> defaultRegionForEnvironment = new HashMap<>();
    private List<ZoneApi> zones = new ArrayList<>();
    private SystemName system;
    private UpgradePolicy upgradePolicy = null;
    private Map<CloudName, UpgradePolicy> osUpgradePolicies = new HashMap<>();

    @Inject
    public ZoneRegistryMock(ConfigserverConfig config) {
        this(SystemName.from(config.system()));
    }

    public ZoneRegistryMock() {
        this(SystemName.main);
    }

    /**
     * This sets the default list of zones contained in this. If your test need a particular set of zones, use
     * {@link #setZones(List)}  instead of changing the default set.}
     */
    public ZoneRegistryMock(SystemName system) {
        this.system = system;
        setZones(List.of(
                ZoneApiMock.fromId("prod.us-east-3"),
                ZoneApiMock.fromId("prod.us-west-1"),
                ZoneApiMock.fromId("prod.us-central-1"),
                ZoneApiMock.fromId("prod.eu-west-1")));
    }

    public ZoneRegistryMock setDeploymentTimeToLive(ZoneId zone, Duration duration) {
        deploymentTimeToLive.put(zone, duration);
        return this;
    }

    public ZoneRegistryMock setDefaultRegionForEnvironment(Environment environment, RegionName region) {
        defaultRegionForEnvironment.put(environment, region);
        return this;
    }

    public ZoneRegistryMock setZones(List<ZoneApi> zones) {
        this.zones = zones;
        return this;
    }

    public ZoneRegistryMock setZones(ZoneApi... zone) {
        return setZones(List.of(zone));
    }

    public ZoneRegistryMock setSystemName(SystemName system) {
        this.system = system;
        return this;
    }

    public ZoneRegistryMock setUpgradePolicy(UpgradePolicy upgradePolicy) {
        this.upgradePolicy = upgradePolicy;
        return this;
    }

    public ZoneRegistryMock setOsUpgradePolicy(CloudName cloud, UpgradePolicy upgradePolicy) {
        osUpgradePolicies.put(cloud, upgradePolicy);
        return this;
    }

    @Override
    public SystemName system() {
        return system;
    }

    @Override
    public ZoneFilter zones() {
        return ZoneFilterMock.from(List.copyOf(zones));
    }

    @Override
    public AthenzService getConfigServerHttpsIdentity(ZoneId zone) {
        return new AthenzService("vespadomain", "provider-" + zone.environment().value() + "-" + zone.region().value());
    }

    @Override
    public AthenzIdentity getNodeAthenzIdentity(ZoneId zoneId, NodeType nodeType) {
        return new AthenzService("vespadomain", "servicename");
    }

    @Override
    public AthenzDomain accessControlDomain() {
        return AthenzDomain.from("vespadomain");
    }

    @Override
    public UpgradePolicy upgradePolicy() {
        return upgradePolicy;
    }

    @Override
    public UpgradePolicy osUpgradePolicy(CloudName cloud) {
        return osUpgradePolicies.get(cloud);
    }

    @Override
    public List<UpgradePolicy> osUpgradePolicies() {
        return ImmutableList.copyOf(osUpgradePolicies.values());
    }

    @Override
    public URI dashboardUrl() {
        return URI.create("https://dashboard.tld");
    }

    @Override
    public URI dashboardUrl(ApplicationId id) {
        return URI.create("https://dashboard.tld/" + id);
    }

    @Override
    public URI dashboardUrl(RunId id) {
        return URI.create("https://dashboard.tld/" + id.application() + "/" + id.type().jobName() + "/" + id.number());
    }

    @Override
    public URI supportUrl() {
        return URI.create("https://help.tld");
    }

    @Override
    public URI badgeUrl() {
        return URI.create("https://badges.tld");
    }

    @Override
    public boolean hasZone(ZoneId zoneId) {
        return zones.stream().anyMatch(zone -> zone.getId().equals(zoneId));
    }

    @Override
    public List<URI> getConfigServerUris(ZoneId zoneId) {
        return Collections.singletonList(URI.create(String.format("https://cfg.%s.test:4443/", zoneId.value())));
    }

    @Override
    public Optional<URI> getConfigServerVipUri(ZoneId zoneId) {
        return Optional.of(URI.create(String.format("https://cfg.%s.test.vip:4443/", zoneId.value())));
    }

    @Override
    public List<URI> getConfigServerApiUris(ZoneId zoneId) {
        List<URI> uris = new ArrayList<URI>();
        uris.add(URI.create(String.format("https://cfg.%s.test:4443/", zoneId.value())));
        uris.add(URI.create(String.format("https://cfg.%s.test.vip:4443/", zoneId.value())));

        return uris;
    }

    @Override
    public Optional<Duration> getDeploymentTimeToLive(ZoneId zoneId) {
        return Optional.ofNullable(deploymentTimeToLive.get(zoneId));
    }

    @Override
    public Optional<RegionName> getDefaultRegion(Environment environment) {
        return Optional.ofNullable(defaultRegionForEnvironment.get(environment));
    }

    @Override
    public URI getMonitoringSystemUri(DeploymentId deploymentId) {
        return URI.create("http://monitoring-system.test/?environment=" + deploymentId.zoneId().environment().value() + "&region="
                          + deploymentId.zoneId().region().value() + "&application=" + deploymentId.applicationId().toShortString());
    }

}
