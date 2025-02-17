// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.deployment;

import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.yahoo.config.provision.SystemName.Public;
import static com.yahoo.config.provision.SystemName.PublicCd;
import static com.yahoo.config.provision.SystemName.cd;
import static com.yahoo.config.provision.SystemName.main;

/** Job types that exist in the build system */
public enum JobType {
//     | enum name ------------| job name ------------------| Zone in main system ---------------------------------------| Zone in CD system -------------------------------------------
    component              ("component",
                            Map.of()),

    systemTest             ("system-test",
                            Map.of(main    , ZoneId.from("test", "us-east-1"),
                                   cd      , ZoneId.from("test", "cd-us-central-1"),
                                   PublicCd, ZoneId.from("test", "aws-us-east-1c"))),

    stagingTest            ("staging-test",
                            Map.of(main    , ZoneId.from("staging", "us-east-3"),
                                   cd      , ZoneId.from("staging", "cd-us-central-1"),
                                   PublicCd, ZoneId.from("staging", "aws-us-east-1c"))),

    productionUsEast3      ("production-us-east-3",
                            Map.of(main, ZoneId.from("prod"   , "us-east-3"))),

    productionUsWest1      ("production-us-west-1",
                            Map.of(main, ZoneId.from("prod"   , "us-west-1"))),

    productionUsCentral1   ("production-us-central-1",
                            Map.of(main, ZoneId.from("prod"   , "us-central-1"))),

    productionApNortheast1 ("production-ap-northeast-1",
                            Map.of(main, ZoneId.from("prod"   , "ap-northeast-1"))),

    productionApNortheast2 ("production-ap-northeast-2",
                            Map.of(main, ZoneId.from("prod"   , "ap-northeast-2"))),

    productionApSoutheast1 ("production-ap-southeast-1",
                            Map.of(main, ZoneId.from("prod"   , "ap-southeast-1"))),

    productionEuWest1      ("production-eu-west-1",
                            Map.of(main, ZoneId.from("prod"   , "eu-west-1"))),

    productionAwsUsEast1a  ("production-aws-us-east-1a",
                            Map.of(main, ZoneId.from("prod"   , "aws-us-east-1a"))),

    productionAwsUsEast1c  ("production-aws-us-east-1c",
                            Map.of(PublicCd, ZoneId.from("prod", "aws-us-east-1c"))),

    productionAwsUsWest2a  ("production-aws-us-west-2a",
                            Map.of(main, ZoneId.from("prod"   , "aws-us-west-2a"))),

    productionAwsUsEast1b  ("production-aws-us-east-1b",
                            Map.of(main, ZoneId.from("prod"   , "aws-us-east-1b"))),

    devUsEast1             ("dev-us-east-1",
                            Map.of(main, ZoneId.from("dev"    , "us-east-1"))),

    devAwsUsEast2a         ("dev-aws-us-east-2a",
                            Map.of(main, ZoneId.from("dev"    , "us-east-1"))),

    productionCdAwsUsEast1a("production-cd-aws-us-east-1a",
                            Map.of(cd  , ZoneId.from("prod"   , "cd-aws-us-east-1a"))),

    productionCdUsCentral1 ("production-cd-us-central-1",
                            Map.of(cd  , ZoneId.from("prod"   , "cd-us-central-1"))),

    // TODO: Cannot remove production-cd-us-central-2 until we know there are no serialized data in controller referencing it
    productionCdUsCentral2 ("production-cd-us-central-2",
                            Map.of(cd  , ZoneId.from("prod"   , "cd-us-central-2"))),

    productionCdUsWest1    ("production-cd-us-west-1",
                            Map.of(cd  , ZoneId.from("prod"   , "cd-us-west-1"))),

    devCdUsCentral1        ("dev-cd-us-central-1",
                            Map.of(cd  , ZoneId.from("dev"    , "cd-us-central-1"))),

    devAwsUsEast1c         ("dev-aws-us-east-1c",
                            Map.of(Public,   ZoneId.from("dev", "aws-us-east-1c"),
                                   PublicCd, ZoneId.from("dev", "aws-us-east-1c"))),

    perfUsEast3            ("perf-us-east-3",
                            Map.of(main, ZoneId.from("perf"   , "us-east-3")));

    private final String jobName;
    private final Map<SystemName, ZoneId> zones;

    JobType(String jobName, Map<SystemName, ZoneId> zones) {
        if (zones.values().stream().map(ZoneId::environment).distinct().count() > 1)
            throw new IllegalArgumentException("All zones of a job must be in the same environment");

        this.jobName = jobName;
        this.zones = zones;
    }

    public String jobName() { return jobName; }

    /** Returns the zone for this job in the given system, or throws if this job does not have a zone */
    public ZoneId zone(SystemName system) {
        if ( ! zones.containsKey(system))
            throw new IllegalArgumentException(this + " does not have any zones in " + system);

        return zones.get(system);
    }

    public static List<JobType> allIn(SystemName system) {
        return Stream.of(values()).filter(job -> job.zones.containsKey(system)).collect(Collectors.toUnmodifiableList());
    }

    /** Returns whether this is a production job */
    public boolean isProduction() { return environment() == Environment.prod; }

    /** Returns whether this is an automated test job */
    public boolean isTest() { return environment() != null && environment().isTest(); }

    /** Returns the environment of this job type, or null if it does not have an environment */
    public Environment environment() {
        if (this == component) return null;
        return zones.values().iterator().next().environment();
    }

    public static Optional<JobType> fromOptionalJobName(String jobName) {
        return Stream.of(values())
                     .filter(jobType -> jobType.jobName.equals(jobName))
                     .findAny();
    }

    public static JobType fromJobName(String jobName) {
        return fromOptionalJobName(jobName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown job name '" + jobName + "'"));
    }

    /** Returns the job type for the given zone */
    public static Optional<JobType> from(SystemName system, ZoneId zone) {
        return Stream.of(values())
                     .filter(job -> zone.equals(job.zones.get(system)))
                     .findAny();
    }

    /** Returns the job job type for the given environment and region or null if none */
    public static Optional<JobType> from(SystemName system, Environment environment, RegionName region) {
        switch (environment) {
            case test: return Optional.of(systemTest);
            case staging: return Optional.of(stagingTest);
        }
        return from(system, ZoneId.from(environment, region));
    }

}
