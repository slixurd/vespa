package com.yahoo.vespa.config.server.tenant;

import com.yahoo.config.model.api.ContainerEndpoint;
import com.yahoo.slime.Slime;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * @author ogronnesby
 */
public class ContainerEndpointSerializerTest {

    @Test
    public void readSingleEndpoint() {
        final var slime = new Slime();
        final var entry = slime.setObject();

        entry.setString("clusterId", "foobar");
        final var entryNames = entry.setArray("names");
        entryNames.addString("a");
        entryNames.addString("b");

        final var endpoint = ContainerEndpointSerializer.endpointFromSlime(slime.get());
        assertEquals("foobar", endpoint.clusterId().toString());
        assertEquals(List.of("a", "b"), endpoint.names());
    }

    @Test
    public void writeReadSingleEndpoint() {
        final var endpoint = new ContainerEndpoint("foo", List.of("a", "b"));
        final var serialized = new Slime();
        ContainerEndpointSerializer.endpointToSlime(serialized.setObject(), endpoint);
        final var deserialized = ContainerEndpointSerializer.endpointFromSlime(serialized.get());

        assertEquals(endpoint, deserialized);
    }

    @Test
    public void writeReadEndpoints() {
        final var endpoints = List.of(new ContainerEndpoint("foo", List.of("a", "b")));
        final var serialized = ContainerEndpointSerializer.endpointListToSlime(endpoints);
        final var deserialized = ContainerEndpointSerializer.endpointListFromSlime(serialized);

        assertEquals(endpoints, deserialized);
    }

}
