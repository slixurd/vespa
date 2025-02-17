package ai.vespa.hosted.api;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * @author jonmv
 */
public class TestConfigTest {

    @Test
    public void testDeserialization() throws IOException {
        TestConfig config = TestConfig.fromJson(Files.readAllBytes(Paths.get("src/test/resources/test-config.json")));
        assertEquals(ApplicationId.from("t", "a", "i"),
                     config.application());
        assertEquals(ZoneId.from("dev", "aws-us-east-1c"),
                     config.zone());
        assertEquals(SystemName.PublicCd,
                     config.system());
        assertEquals(Map.of(ZoneId.from("dev", "aws-us-east-1c"),
                            Map.of("default", URI.create("https://dev.endpoint:443/")),
                            ZoneId.from("prod", "aws-us-east-1a"),
                            Map.of("default", URI.create("https://prod.endpoint:443/"))),
                     config.deployments());
    }

    @Test
    public void testClustersOnly() throws IOException {
        TestConfig config = TestConfig.fromJson(Files.readAllBytes(Paths.get("src/test/resources/clusters-only-config.json")));
        assertEquals(ApplicationId.defaultId(),
                     config.application());
        assertEquals(Map.of("default", URI.create("https://localhost:8080/"),
                            "container", URI.create("https://localhost:8081/")),
                     config.deployments().get(ZoneId.defaultId()));
    }

}
