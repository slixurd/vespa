package com.yahoo.vespa.model.admin.metricsproxy;

import ai.vespa.metricsproxy.metric.dimensions.NodeDimensionsConfig;
import ai.vespa.metricsproxy.rpc.RpcConnectorConfig;
import ai.vespa.metricsproxy.service.VespaServicesConfig;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyContainer.NodeDimensionNames;
import com.yahoo.vespa.model.test.VespaModelTester;
import org.junit.Test;

import static com.yahoo.config.model.api.container.ContainerServiceType.METRICS_PROXY_CONTAINER;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.CLUSTER_CONFIG_ID;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.CONTAINER_CONFIG_ID;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.TestMode.hosted;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.TestMode.self_hosted;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.getModel;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.getNodeDimensionsConfig;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.getRpcConnectorConfig;
import static com.yahoo.vespa.model.admin.metricsproxy.MetricsProxyModelTester.getVespaServicesConfig;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author gjoranv
 */
public class MetricsProxyContainerTest {

    @Test
    public void one_metrics_proxy_container_is_added_to_every_node() {
        var numberOfHosts = 4;
        var tester = new VespaModelTester();
        tester.addHosts(numberOfHosts);

        VespaModel model = tester.createModel(servicesWithManyNodes(), true);
        assertThat(model.getRoot().getHostSystem().getHosts().size(), is(numberOfHosts));

        for (var host : model.getHostSystem().getHosts()) {
            assertThat(host.getService(METRICS_PROXY_CONTAINER.serviceName), notNullValue());

            long metricsProxies =  host.getServices().stream()
                    .filter(s -> s.getClass().equals(MetricsProxyContainer.class))
                    .count();
            assertThat(metricsProxies, is(1L));
        }
    }

    @Test
    public void metrics_proxy_requires_less_memory_than_other_containers() {
        VespaModel model = getModel(servicesWithContent(), self_hosted);
        MetricsProxyContainer container = (MetricsProxyContainer)model.id2producer().get(CONTAINER_CONFIG_ID);
        assertThat(container.getStartupCommand(), containsString("-Xms32m"));
        assertThat(container.getStartupCommand(), containsString("-Xmx512m"));
    }

    @Test
    public void http_server_is_running_on_expected_port() {
        VespaModel model = getModel(servicesWithContent(), self_hosted);
        MetricsProxyContainer container = (MetricsProxyContainer)model.id2producer().get(CONTAINER_CONFIG_ID);
        assertEquals(19092, container.getSearchPort());
        assertEquals(19092, container.getHealthPort());

        assertTrue(container.getPortsMeta().getTagsAt(0).contains("http"));
        assertTrue(container.getPortsMeta().getTagsAt(0).contains("state"));
    }

    @Test
    public void metrics_rpc_server_is_running_on_expected_port() {
        VespaModel model = getModel(servicesWithContent(), self_hosted);
        MetricsProxyContainer container = (MetricsProxyContainer)model.id2producer().get(CONTAINER_CONFIG_ID);

        int offset = 3;
        assertEquals(2, container.getPortsMeta().getTagsAt(offset).size());
        assertTrue(container.getPortsMeta().getTagsAt(offset).contains("rpc"));
        assertTrue(container.getPortsMeta().getTagsAt(offset).contains("metrics"));

        RpcConnectorConfig config = getRpcConnectorConfig(model);
        assertEquals(19095, config.port());
    }

    @Test
    public void admin_rpc_server_is_running() {
        VespaModel model = getModel(servicesWithContent(), self_hosted);
        MetricsProxyContainer container = (MetricsProxyContainer)model.id2producer().get(CONTAINER_CONFIG_ID);

        int offset = 2;
        assertEquals(2, container.getPortsMeta().getTagsAt(offset).size());
        assertTrue(container.getPortsMeta().getTagsAt(offset).contains("rpc"));
        assertTrue(container.getPortsMeta().getTagsAt(offset).contains("admin"));
    }

    @Test
    public void hosted_application_propagates_node_dimensions() {
        String services = servicesWithContent();
        VespaModel hostedModel = getModel(services, hosted);
        assertEquals(1, hostedModel.getHosts().size());
        String configId = CLUSTER_CONFIG_ID + "/" + hostedModel.getHosts().iterator().next().getHostname();
        NodeDimensionsConfig config = getNodeDimensionsConfig(hostedModel, configId);

        assertEquals("content", config.dimensions(NodeDimensionNames.CLUSTER_TYPE));
        assertEquals("my-content", config.dimensions(NodeDimensionNames.CLUSTER_ID));
    }


    @Test
    public void vespa_services_config_has_all_services() {
        VespaServicesConfig vespaServicesConfig = getVespaServicesConfig(servicesWithContent());
        assertEquals(6, vespaServicesConfig.service().size());

        for (var service : vespaServicesConfig.service()) {
            if (service.configId().equals("admin/cluster-controllers/0")) {
                assertEquals("Wrong service name", "container-clustercontroller", service.name());
                assertEquals(1, service.dimension().size());
                assertEquals("clustername", service.dimension(0).key());
                assertEquals("cluster-controllers", service.dimension(0).value());
            }
        }
    }

    @Test
    public void vespa_services_config_has_service_dimensions() {
        VespaServicesConfig vespaServicesConfig = getVespaServicesConfig(servicesWithContent());
        for (var service : vespaServicesConfig.service()) {
            if (service.configId().equals("admin/cluster-controllers/0")) {
                assertEquals(1, service.dimension().size());
                assertEquals("clustername", service.dimension(0).key());
                assertEquals("cluster-controllers", service.dimension(0).value());
            }
        }
    }


    private static String servicesWithManyNodes() {
        return String.join("\n",
                           "<services>",
                           "    <container version='1.0' id='foo'>",
                           "        <nodes count='2'/>",
                           "    </container>",
                           "    <content id='my-content' version='1.0'>",
                           "        <documents />",
                           "        <nodes count='2'/>",
                           "    </content>",
                           "</services>");
    }

    private static String servicesWithContent() {
        return String.join("\n",
                           "<services>",
                           "    <admin version='4.0'>",
                           "        <adminserver hostalias='node1'/>",
                           "    </admin>",
                           "    <content version='1.0' id='my-content'>",
                           "        <documents />",
                           "        <nodes count='1' />",
                           "    </content>",
                           "</services>"
        );
    }

}
