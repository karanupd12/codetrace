package dev.codetrace.source.service;

import dev.codetrace.config.IngestionProperties;
import dev.codetrace.source.model.SourceType;
import dev.codetrace.common.web.ApiException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates repository sources before anything touches them. Used by the API at registration and again by the
 * worker right before access (DNS and filesystem state can change in between).
 */
@Component
public class SourceValidator {
    public record Validated(SourceType type, String location, String displayName) {
    }

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final Pattern URL_PATH = Pattern.compile("^(/[A-Za-z0-9._-]{1,100}){2,5}/?$");

    private final IngestionProperties props;
    private final HostResolver resolver;

    @Autowired
    public SourceValidator(IngestionProperties props) {
        this(props, InetAddress::getAllByName);
    }

    public SourceValidator(IngestionProperties props, HostResolver resolver) {
        this.props = props;
        this.resolver = resolver;
    }

    public Validated validate(SourceType type, String location) {
        if (location == null || location.isBlank() || location.length() > 2048 || location.indexOf('\0') >= 0) {
            throw ApiException.badRequest("location is required and must be at most 2048 characters.");
        }
        return type == SourceType.GIT_URL ? validateGitUrl(location.trim()) : validateLocal(location.trim());
    }

    // ---- GIT_URL ---------------------------------------------------------------------------------------------

    Validated validateGitUrl(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("Not a valid URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw ApiException.badRequest("Only https:// repository URLs are supported.");
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.badRequest("Credentials in the URL are not allowed. Only public repositories are supported.");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw ApiException.badRequest("URL must not contain a query string or fragment.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw ApiException.badRequest("Non-standard ports are not allowed.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!props.allowedHosts().contains(host)) {
            throw ApiException.badRequest("Host is not allowed. Allowed hosts: " + String.join(", ", props.allowedHosts()));
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!URL_PATH.matcher(path).matches()) {
            throw ApiException.badRequest("URL must look like https://" + host + "/owner/repo");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw ApiException.badRequest("Invalid path in URL.");
            }
        }
        String canonicalPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (canonicalPath.endsWith(".git")) {
            canonicalPath = canonicalPath.substring(0, canonicalPath.length() - 4);
        }
        assertPublicHost(host);
        String[] parts = canonicalPath.substring(1).split("/");
        String display = parts[parts.length - 2] + "/" + parts[parts.length - 1];
        return new Validated(SourceType.GIT_URL, "https://" + host + canonicalPath, display);
    }

    /** SSRF guard: every address the host resolves to must be publicly routable. */
    public void assertPublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("Host could not be resolved.");
        }
        if (addresses.length == 0) {
            throw ApiException.badRequest("Host could not be resolved.");
        }
        for (InetAddress a : addresses) {
            if (isNonPublic(a)) {
                throw ApiException.badRequest("Host resolves to a non-public address.");
            }
        }
    }

    static boolean isNonPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
            return true; // fc00::/7 unique local
        }
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10 CGNAT
    }

    // ---- LOCAL -----------------------------------------------------------------------------------------------

    Validated validateLocal(String raw) {
        if (props.localRoots().isEmpty()) {
            throw ApiException.badRequest("Local repositories are disabled on this server.");
        }
        Path real;
        try {
            real = Path.of(raw).toRealPath();
        } catch (InvalidPathException | IOException e) {
            throw ApiException.badRequest("Local path does not exist or is not readable.");
        }
        if (!Files.isDirectory(real)) {
            throw ApiException.badRequest("Local path is not a directory.");
        }
        boolean underRoot = false;
        for (String root : props.localRoots()) {
            try {
                if (real.startsWith(Path.of(root).toRealPath())) {
                    underRoot = true;
                    break;
                }
            } catch (InvalidPathException | IOException ignored) {
                // a misconfigured root simply never matches
            }
        }
        if (!underRoot) {
            throw ApiException.badRequest("Local path is outside the configured repository roots.");
        }
        if (gitDirOf(real) == null) {
            throw ApiException.badRequest("Local path is not a Git repository.");
        }
        Path name = real.getFileName();
        return new Validated(SourceType.LOCAL, real.toString(), name == null ? "repository" : name.toString());
    }

    /** Returns the git directory (".git" folder, or the path itself if bare), or null if it is not a repository. */
    public static Path gitDirOf(Path repoPath) {
        Path dotGit = repoPath.resolve(".git");
        if (Files.isDirectory(dotGit) && !Files.isSymbolicLink(dotGit)) {
            return dotGit;
        }
        if (Files.isRegularFile(repoPath.resolve("HEAD")) && Files.isDirectory(repoPath.resolve("objects"))) {
            return repoPath;
        }
        return null;
    }
}
