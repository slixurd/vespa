/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.http;

import ai.vespa.metricsproxy.TestUtil;
import ai.vespa.metricsproxy.core.ConsumersConfig;
import ai.vespa.metricsproxy.core.ConsumersConfig.Consumer;
import ai.vespa.metricsproxy.core.MetricsConsumers;
import ai.vespa.metricsproxy.core.MetricsManager;
import ai.vespa.metricsproxy.metric.HealthMetric;
import ai.vespa.metricsproxy.metric.Metric;
import ai.vespa.metricsproxy.metric.dimensions.ApplicationDimensions;
import ai.vespa.metricsproxy.metric.dimensions.ApplicationDimensionsConfig;
import ai.vespa.metricsproxy.metric.dimensions.NodeDimensions;
import ai.vespa.metricsproxy.metric.dimensions.NodeDimensionsConfig;
import ai.vespa.metricsproxy.metric.model.MetricsPacket;
import ai.vespa.metricsproxy.metric.model.json.GenericJsonModel;
import ai.vespa.metricsproxy.metric.model.json.GenericMetrics;
import ai.vespa.metricsproxy.metric.model.json.GenericService;
import ai.vespa.metricsproxy.service.DownService;
import ai.vespa.metricsproxy.service.DummyService;
import ai.vespa.metricsproxy.service.VespaService;
import ai.vespa.metricsproxy.service.VespaServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.yahoo.container.jdisc.RequestHandlerTestDriver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import static ai.vespa.metricsproxy.core.VespaMetrics.INSTANCE_DIMENSION_ID;
import static ai.vespa.metricsproxy.http.MetricsHandler.V1_PATH;
import static ai.vespa.metricsproxy.http.MetricsHandler.VALUES_PATH;
import static ai.vespa.metricsproxy.http.ValuesFetcher.DEFAULT_PUBLIC_CONSUMER_ID;
import static ai.vespa.metricsproxy.metric.model.ServiceId.toServiceId;
import static ai.vespa.metricsproxy.metric.model.StatusCode.DOWN;
import static ai.vespa.metricsproxy.metric.model.json.JacksonUtil.createObjectMapper;
import static ai.vespa.metricsproxy.service.DummyService.METRIC_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author gjoranv
 */
@SuppressWarnings("UnstableApiUsage")
public class MetricsHandlerTest {

    private static final List<VespaService> testServices = ImmutableList.of(
            new DummyService(0, ""),
            new DummyService(1, ""),
            new DownService(HealthMetric.getDown("No response")));

    private static final VespaServices vespaServices = new VespaServices(testServices);

    private static final String DEFAULT_CONSUMER = "default";
    private static final String CUSTOM_CONSUMER = "custom-consumer";

    private static final String CPU_METRIC = "cpu";

    private static final String URI_BASE = "http://localhost";
    private static final String V1_URI = URI_BASE + V1_PATH;
    private static final String VALUES_URI = URI_BASE + VALUES_PATH;


    private static RequestHandlerTestDriver testDriver;

    @BeforeClass
    public static void setup() {
        MetricsManager metricsManager = TestUtil.createMetricsManager(vespaServices, getMetricsConsumers(), getApplicationDimensions(), getNodeDimensions());
        metricsManager.setExtraMetrics(ImmutableList.of(
                new MetricsPacket.Builder(toServiceId("foo"))
                        .timestamp(Instant.now().getEpochSecond())
                        .putMetrics(ImmutableList.of(new Metric(CPU_METRIC, 12.345)))));
        MetricsHandler handler = new MetricsHandler(Executors.newSingleThreadExecutor(), metricsManager, vespaServices, getMetricsConsumers());
        testDriver = new RequestHandlerTestDriver(handler);
    }

    private GenericJsonModel getResponseAsJsonModel(String consumer) {
        String response = testDriver.sendRequest(VALUES_URI + "?consumer=" + consumer).readAll();
        try {
            return createObjectMapper().readValue(response, GenericJsonModel.class);
        } catch (IOException e) {
            fail("Failed to create json model: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Test
    public void v1_response_contains_values_uri() throws Exception {
        String response = testDriver.sendRequest(V1_URI).readAll();
        JSONObject root = new JSONObject(response);
        assertTrue(root.has("resources"));

        JSONArray resources = root.getJSONArray("resources");
        assertEquals(1, resources.length());

        JSONObject valuesUrl = resources.getJSONObject(0);
        assertEquals(VALUES_URI, valuesUrl.getString("url"));
    }

    @Ignore
    @Test
    public void visually_inspect_values_response() throws Exception{
        String response = testDriver.sendRequest(VALUES_URI).readAll();
        ObjectMapper mapper = createObjectMapper();
        var jsonModel = mapper.readValue(response, GenericJsonModel.class);
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonModel));
    }

    @Test
    public void no_explicit_consumer_gives_the_default_consumer() {
        String responseDefaultConsumer = testDriver.sendRequest(VALUES_URI + "?consumer=default").readAll();
        String responseNoConsumer = testDriver.sendRequest(VALUES_URI).readAll();
        assertEqualsExceptTimestamps(responseDefaultConsumer, responseNoConsumer);
    }

    @Test
    public void unknown_consumer_gives_the_default_consumer() {
        String response = testDriver.sendRequest(VALUES_URI).readAll();
        String responseUnknownConsumer = testDriver.sendRequest(VALUES_URI + "?consumer=not_defined").readAll();
        assertEqualsExceptTimestamps(response, responseUnknownConsumer);
    }

    private void assertEqualsExceptTimestamps(String s1, String s2) {
        assertEquals(replaceTimestamps(s1), replaceTimestamps(s2));
    }

    @Test
    public void response_contains_node_metrics() {
        GenericJsonModel jsonModel = getResponseAsJsonModel(DEFAULT_CONSUMER);

        assertNotNull(jsonModel.node);
        assertEquals(1, jsonModel.node.metrics.size());
        assertEquals(12.345, jsonModel.node.metrics.get(0).values.get(CPU_METRIC), 0.0001d);
    }

    @Test
    public void response_contains_service_metrics() {
        GenericJsonModel jsonModel = getResponseAsJsonModel(DEFAULT_CONSUMER);

        assertEquals(2, jsonModel.services.size());
        GenericService dummyService = jsonModel.services.get(0);
        assertEquals(2, dummyService.metrics.size());

        GenericMetrics dummy0Metrics = getMetricsForInstance("dummy0", dummyService);
        assertEquals(1L, dummy0Metrics.values.get(METRIC_1).longValue());
        assertEquals("default-val", dummy0Metrics.dimensions.get("consumer-dim"));

        GenericMetrics dummy1Metrics = getMetricsForInstance("dummy1", dummyService);
        assertEquals(6L, dummy1Metrics.values.get(METRIC_1).longValue());
        assertEquals("default-val", dummy1Metrics.dimensions.get("consumer-dim"));
    }

    @Test
    public void all_consumers_get_health_from_service_that_is_down() {
        assertDownServiceHealth(DEFAULT_CONSUMER);
        assertDownServiceHealth(CUSTOM_CONSUMER);
    }

    @Test
    public void all_timestamps_are_equal_and_non_zero() {
        GenericJsonModel jsonModel = getResponseAsJsonModel(DEFAULT_CONSUMER);

        Long nodeTimestamp = jsonModel.node.timestamp;
        assertNotEquals(0L, (long) nodeTimestamp);
        for (var service : jsonModel.services)
            assertEquals(nodeTimestamp, service.timestamp);
    }

    @Test
    public void custom_consumer_gets_only_its_whitelisted_metrics() {
        GenericJsonModel jsonModel = getResponseAsJsonModel(CUSTOM_CONSUMER);

        assertNotNull(jsonModel.node);
        // TODO: see comment in ExternalMetrics.setExtraMetrics
        // assertEquals(0, jsonModel.node.metrics.size());

        assertEquals(2, jsonModel.services.size());
        GenericService dummyService = jsonModel.services.get(0);
        assertEquals(2, dummyService.metrics.size());

        GenericMetrics dummy0Metrics = getMetricsForInstance("dummy0", dummyService);
        assertEquals("custom-val", dummy0Metrics.dimensions.get("consumer-dim"));

        GenericMetrics dummy1Metrics = getMetricsForInstance("dummy1", dummyService);
        assertEquals("custom-val", dummy1Metrics.dimensions.get("consumer-dim"));
    }

    private void assertDownServiceHealth(String consumer) {
        GenericJsonModel jsonModel = getResponseAsJsonModel(consumer);

        GenericService downService = jsonModel.services.get(1);
        assertEquals(DOWN.status, downService.status.code);
        assertEquals("No response", downService.status.description);

        // Service should output metric dimensions, even without metrics, because they contain important info about the service.
        assertEquals(1, downService.metrics.size());
        assertEquals(0, downService.metrics.get(0).values.size());
        assertFalse(downService.metrics.get(0).dimensions.isEmpty());
        assertEquals(DownService.NAME, downService.metrics.get(0).dimensions.get(INSTANCE_DIMENSION_ID.id));
    }

    private String replaceTimestamps(String s) {
        return s.replaceAll("timestamp\":\\d+,", "timestamp\":1,");
    }

    private static GenericMetrics getMetricsForInstance(String instance, GenericService service) {
        for (var metrics : service.metrics) {
            if (metrics.dimensions.get(INSTANCE_DIMENSION_ID.id).equals(instance))
                return metrics;
        }
        fail("Could not find metrics for service instance " + instance);
        throw new RuntimeException();
    }

    private static MetricsConsumers getMetricsConsumers() {
        var defaultConsumerDimension = new Consumer.Metric.Dimension.Builder()
                .key("consumer-dim").value("default-val");

        var customConsumerDimension = new Consumer.Metric.Dimension.Builder()
                .key("consumer-dim").value("custom-val");

        return new MetricsConsumers(new ConsumersConfig.Builder()
                                            .consumer(new Consumer.Builder()
                                                              .name(DEFAULT_PUBLIC_CONSUMER_ID.id)
                                                              .metric(new Consumer.Metric.Builder()
                                                                              .name(CPU_METRIC)
                                                                              .outputname(CPU_METRIC))
                                                              .metric(new Consumer.Metric.Builder()
                                                                              .name(METRIC_1)
                                                                              .outputname(METRIC_1)
                                                                              .dimension(defaultConsumerDimension)))
                                            .consumer(new Consumer.Builder()
                                                    .name(CUSTOM_CONSUMER)
                                                              .metric(new Consumer.Metric.Builder()
                                                                              .name(METRIC_1)
                                                                              .outputname(METRIC_1)
                                                                              .dimension(customConsumerDimension)))
                                            .build());
    }

    private static ApplicationDimensions getApplicationDimensions() {
        return new ApplicationDimensions(new ApplicationDimensionsConfig.Builder().build());
    }

    private static NodeDimensions getNodeDimensions() {
        return new NodeDimensions(new NodeDimensionsConfig.Builder().build());
    }

}
