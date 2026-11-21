package dev.codetrace.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.source.model.SourceType;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.common.web.ApiException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceValidatorTest {
    @TempDir
    Path tmp;

    private static SourceValidator.HostResolver resolvingTo(String... ips) {
        return host -> {
            InetAddress[] out = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) out[i] = InetAddress.getByName(ips[i]);
            return out;
        };
    }

    private SourceValidator validator(SourceValidator.HostResolver r, List<String> roots) {
        return new SourceValidator(GitFixtures.props(tmp.resolve("ws"), roots), r);
    }

    // ---- GIT_URL ----

    @Test
    void acceptsAndCanonicalizesPublicGithubUrls() {
        SourceValidator v = validator(resolvingTo("140.82.112.3"), List.of());

        var ok = v.validate(SourceType.GIT_URL, "https://GitHub.com/Owner/Repo.git/");

        assertThat(ok.location()).isEqualTo("https://github.com/Owner/Repo");
        assertThat(ok.displayName()).isEqualTo("Owner/Repo");
        assertThat(v.validate(SourceType.GIT_URL, "https://github.com/Owner/Repo").location())
                .isEqualTo(ok.location());
    }

    @Test
    void acceptsGitlabSubgroups() {
        SourceValidator v = validator(resolvingTo("172.65.251.78"), List.of());

        assertThat(v.validate(SourceType.GIT_URL, "https://gitlab.com/group/sub/repo").displayName())
                .isEqualTo("sub/repo");
    }

    @Test
    void rejectsUnsafeSchemesAndShapes() {
        SourceValidator v = validator(resolvingTo("140.82.112.3"), List.of());
        List<String> bad = List.of(
                "http://github.com/o/r",
                "ssh://git@github.com/o/r.git",
                "git@github.com:o/r.git",
                "git://github.com/o/r",
                "file:///etc/passwd",
                "ftp://github.com/o/r",
                "https://user:pass@github.com/o/r",
                "https://token@github.com/o/r",
                "https://github.com:8443/o/r",
                "https://github.com/o/r?x=1",
                "https://github.com/o/r#frag",
                "https://github.com/o",
                "https://github.com/",
                "https://github.com/o/../r",
                "https://github.com/o/./r",
                "https://github.com/o/r/../../x",
                "https://github.com/%2e%2e/r",
                "not a url",
                "https://evil.example.com/o/r",
                "https://github.com.evil.com/o/r",
                "https://notgithub.com/o/r");
        for (String url : bad) {
            assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, url))
                    .as(url).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void rejectsHostsResolvingToNonPublicAddresses() {
        for (String ip : List.of("127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.9", "169.254.169.254",
                "100.64.1.1", "0.0.0.0", "::1", "fd00::1", "fe80::1")) {
            SourceValidator v = validator(resolvingTo(ip), List.of());
            assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, "https://github.com/o/r"))
                    .as(ip).isInstanceOf(ApiException.class).hasMessageContaining("non-public");
        }
    }

    @Test
    void rejectsIfAnyResolvedAddressIsPrivate() {
        SourceValidator v = validator(resolvingTo("140.82.112.3", "10.0.0.1"), List.of());

        assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, "https://github.com/o/r"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsUnresolvableHosts() {
        SourceValidator v = validator(host -> {
            throw new UnknownHostException(host);
        }, List.of());

        assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, "https://github.com/o/r"))
                .isInstanceOf(ApiException.class).hasMessageContaining("resolved");
    }

    @Test
    void rejectsBlankOversizedAndNullByteLocations() {
        SourceValidator v = validator(resolvingTo("140.82.112.3"), List.of());

        assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, "  ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, "https://github.com/o/r\0"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> v.validate(SourceType.GIT_URL, "https://github.com/o/" + "x".repeat(3000)))
                .isInstanceOf(ApiException.class);
    }

    // ---- LOCAL ----

    @Test
    void localSourcesAreDisabledWithoutConfiguredRoots() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("r/.git"));
        SourceValidator v = validator(resolvingTo("1.1.1.1"), List.of());

        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, repo.getParent().toString()))
                .isInstanceOf(ApiException.class).hasMessageContaining("disabled");
    }

    @Test
    void acceptsRepositoriesUnderAConfiguredRoot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path repo = Files.createDirectories(root.resolve("proj/.git"));
        SourceValidator v = validator(resolvingTo("1.1.1.1"), List.of(root.toString()));

        var ok = v.validate(SourceType.LOCAL, repo.getParent().toString());

        assertThat(ok.displayName()).isEqualTo("proj");
        assertThat(Path.of(ok.location())).isEqualTo(repo.getParent().toRealPath());
    }

    @Test
    void rejectsPathsOutsideRootsAndTraversal() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("inside/.git"));
        Path outside = Files.createDirectories(tmp.resolve("outside/.git")).getParent();
        SourceValidator v = validator(resolvingTo("1.1.1.1"), List.of(root.toString()));

        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, outside.toString()))
                .isInstanceOf(ApiException.class).hasMessageContaining("outside");
        // ../ traversal that escapes the root is resolved before the check
        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, root.resolve("inside/../../outside").toString()))
                .isInstanceOf(ApiException.class);
        // a sibling whose name merely starts with the root's name is not "under" it
        Path sibling = Files.createDirectories(tmp.resolve("root-evil/.git")).getParent();
        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, sibling.toString()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMissingNonDirectoryAndNonGitPaths() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path plain = Files.createDirectories(root.resolve("plain"));
        Path file = Files.writeString(root.resolve("file.txt"), "x");
        SourceValidator v = validator(resolvingTo("1.1.1.1"), List.of(root.toString()));

        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, root.resolve("missing").toString()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, file.toString())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, plain.toString()))
                .isInstanceOf(ApiException.class).hasMessageContaining("not a Git repository");
    }

    @Test
    void errorMessagesNeverContainServerPaths() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        SourceValidator v = validator(resolvingTo("1.1.1.1"), List.of(root.toString()));
        String secret = tmp.toString();

        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, root.resolve("nope").toString()))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
        assertThatThrownBy(() -> v.validate(SourceType.LOCAL, tmp.toString()))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
    }
}
