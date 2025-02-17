package ai.vespa.hosted.api;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Utilities and common definitions of system properties defining a Vespa application project.
 *
 * @author jonmv
 */
public class Properties {

    public static ApplicationId application() {
        return ApplicationId.from(requireNonBlankProperty("tenant"),
                                  requireNonBlankProperty("application"),
                                  getNonBlankProperty("instance").orElse("default"));
    }

    public static Optional<Environment> environment() {
        return getNonBlankProperty("environment").map(Environment::from);
    }

    public static Optional<RegionName> region() {
        return getNonBlankProperty("region").map(RegionName::from);
    }

    public static URI endpoint() {
        return URI.create(requireNonBlankProperty("endpoint"));
    }

    public static Path privateKeyFile() {
        return Paths.get(requireNonBlankProperty("privateKeyFile"));
    }

    public static Path certificateFile() {
        return Paths.get(requireNonBlankProperty("certificateFile"));
    }

    /** Returns the system property with the given name if it is set, or empty. */
    public static Optional<String> getNonBlankProperty(String name) {
        return Optional.ofNullable(System.getProperty(name)).filter(value -> ! value.isBlank());
    }

    /** Returns the system property with the given name if it is set, or throws. */
    public static String requireNonBlankProperty(String name) {
        return getNonBlankProperty(name).orElseThrow(() -> new IllegalStateException("Missing required property '" + name + "'"));
    }

}
