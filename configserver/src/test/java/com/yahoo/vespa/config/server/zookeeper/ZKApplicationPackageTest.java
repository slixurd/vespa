// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.zookeeper;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.provision.AllocatedHosts;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provisioning.FlavorsConfig;
import com.yahoo.io.IOUtils;
import com.yahoo.path.Path;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.curator.mock.MockCurator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.yahoo.config.provision.serialization.AllocatedHostsSerializer.toJson;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class ZKApplicationPackageTest {

    private static final String APP = "src/test/apps/zkapp";
    private static final String TEST_FLAVOR_NAME = "test-flavor";
    private static final Optional<Flavor> TEST_FLAVOR = new MockNodeFlavors().getFlavor(TEST_FLAVOR_NAME);
    private static final AllocatedHosts ALLOCATED_HOSTS = AllocatedHosts.withHosts(
            Collections.singleton(new HostSpec("foo.yahoo.com", Collections.emptyList(), TEST_FLAVOR, Optional.empty(),
                                               Optional.of(com.yahoo.component.Version.fromString("6.0.1")))));

    private ConfigCurator configCurator;

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Before
    public void setup() {
        configCurator = ConfigCurator.create(new MockCurator());
    }

    @Test
    public void testBasicZKFeed() throws IOException {
        feed(configCurator, new File(APP));
        ZKApplicationPackage zkApp = new ZKApplicationPackage(configCurator, Path.fromString("/0"), Optional.of(new MockNodeFlavors()));
        assertTrue(Pattern.compile(".*<slobroks>.*",Pattern.MULTILINE+Pattern.DOTALL).matcher(IOUtils.readAll(zkApp.getServices())).matches());
        assertTrue(Pattern.compile(".*<alias>.*",Pattern.MULTILINE+Pattern.DOTALL).matcher(IOUtils.readAll(zkApp.getHosts())).matches());
        assertTrue(Pattern.compile(".*<slobroks>.*",Pattern.MULTILINE+Pattern.DOTALL).matcher(IOUtils.readAll(zkApp.getFile(Path.fromString("services.xml")).createReader())).matches());
        DeployState deployState = new DeployState.Builder().applicationPackage(zkApp).build();
        assertEquals(deployState.getSearchDefinitions().size(), 5);
        assertEquals(zkApp.searchDefinitionContents().size(), 5);
        assertEquals(IOUtils.readAll(zkApp.getRankingExpression("foo.expression")), "foo()+1\n");
        assertEquals(zkApp.getFiles(Path.fromString(""), "xml").size(), 3);
        assertEquals(zkApp.getFileReference(Path.fromString("components/file.txt")).getAbsolutePath(), "/home/vespa/test/file.txt");
        try (Reader foo = zkApp.getFile(Path.fromString("files/foo.json")).createReader()) {
            assertEquals(IOUtils.readAll(foo), "foo : foo\n");
        }
        try (Reader bar = zkApp.getFile(Path.fromString("files/sub/bar.json")).createReader()) {
            assertEquals(IOUtils.readAll(bar), "bar : bar\n");
        }
        assertTrue(zkApp.getFile(Path.createRoot()).exists());
        assertTrue(zkApp.getFile(Path.createRoot()).isDirectory());
        Version goodVersion = new Version(3, 0, 0);
        assertTrue(zkApp.getFileRegistries().containsKey(goodVersion));
        assertFalse(zkApp.getFileRegistries().containsKey(new Version(0, 0, 0)));
        assertThat(zkApp.getFileRegistries().get(goodVersion).fileSourceHost(), is("dummyfiles"));
        AllocatedHosts readInfo = zkApp.getAllocatedHosts().get();
        assertThat(Utf8.toString(toJson(readInfo)), is(Utf8.toString(toJson(ALLOCATED_HOSTS))));
        assertThat(readInfo.getHosts().iterator().next().flavor(), is(TEST_FLAVOR));
        assertEquals("6.0.1", readInfo.getHosts().iterator().next().version().get().toString());
        assertTrue(zkApp.getDeployment().isPresent());
        assertThat(DeploymentSpec.fromXml(zkApp.getDeployment().get()).globalServiceId().get(), is("mydisc"));
    }

    private void feed(ConfigCurator zk, File dirToFeed) throws IOException {
        assertTrue(dirToFeed.isDirectory());
        zk.feedZooKeeper(dirToFeed, "/0" + ConfigCurator.USERAPP_ZK_SUBPATH, null, true);
        String metaData = "{\"deploy\":{\"user\":\"foo\",\"from\":\"bar\",\"timestamp\":1},\"application\":{\"name\":\"foo\",\"checksum\":\"abc\",\"generation\":4,\"previousActiveGeneration\":3}}";
        zk.putData("/0", ConfigCurator.META_ZK_PATH, metaData);
        zk.putData("/0/" + ZKApplicationPackage.fileRegistryNode + "/3.0.0", "dummyfiles");
        zk.putData("/0/" + ZKApplicationPackage.allocatedHostsNode, toJson(ALLOCATED_HOSTS));
    }

    private static class MockNodeFlavors extends NodeFlavors{

        MockNodeFlavors() { super(flavorsConfig()); }

        private static FlavorsConfig flavorsConfig() {
            return new FlavorsConfig(new FlavorsConfig.Builder()
                            .flavor(new FlavorsConfig.Flavor.Builder().name(TEST_FLAVOR_NAME))
            );
        }
    }

}
