package fixture.placement;

import io.github.copule.liminaldimension.playerdata.PlayerDimensionProbe;
import io.github.copule.liminaldimension.playerdata.PlayerDimensionProbe.Status;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class PlacementScope {
    static final String WORLD_DIRECTORY = "placement-integration-world";
    static final String MARKER_FILE = ".liminal-placement-experiment";
    static final String MARKER_TEXT = "liminal-placement-integration-v1\n";
    private static final byte[] MARKER_BYTES = MARKER_TEXT.getBytes(StandardCharsets.US_ASCII);

    private PlacementScope() {}

    static boolean isLocalDedicatedServer(boolean dedicated, String bindAddress) {
        return dedicated && "127.0.0.1".equals(bindAddress);
    }

    static boolean isLocalTcp(boolean memory, SocketAddress local, SocketAddress remote) {
        return !memory && isExactLoopback(local) && isExactLoopback(remote);
    }

    private static boolean isExactLoopback(SocketAddress address) {
        return address instanceof InetSocketAddress socket
                && socket.getAddress() instanceof Inet4Address ip
                && "127.0.0.1".equals(ip.getHostAddress());
    }

    static boolean hasAuthentication(boolean configuredOnline, boolean authenticationInUse,
            boolean singleplayerProfile, boolean encrypted, boolean acceptsTransfers) {
        return configuredOnline && authenticationInUse && !singleplayerProfile && encrypted && !acceptsTransfers;
    }

    static Path experimentWorld(Path workingDirectory, Path authoritativePlayerData) {
        Path expected = workingDirectory.resolve(WORLD_DIRECTORY);
        // Keep the storage path's lexical spelling. A dot segment cannot be normalized into scope.
        return expected.resolve("players/data").equals(authoritativePlayerData.toAbsolutePath()) ? expected : null;
    }

    static Status inspect(PlacementAdmission.Input input) {
        try {
            Path root = input.world();
            if (!root.equals(root.toRealPath())) {
                return Status.UNCONFIRMED;
            }
            Path marker = root.resolve(MARKER_FILE);
            var attrs = Files.readAttributes(marker, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.size() != MARKER_BYTES.length) {
                return Status.UNCONFIRMED;
            }
            try (var stream = Files.newInputStream(marker, NOFOLLOW_LINKS)) {
                if (!Arrays.equals(MARKER_BYTES, stream.readNBytes(MARKER_BYTES.length + 1))) {
                    return Status.UNCONFIRMED;
                }
            }
            return PlayerDimensionProbe.inspect(root, input.player(), input.loaded());
        } catch (IOException | RuntimeException unavailable) {
            return Status.UNCONFIRMED;
        }
    }
}
