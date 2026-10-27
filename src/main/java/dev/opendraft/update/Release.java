package dev.opendraft.update;

import dev.opendraft.net.OpenDraftHost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * The newest OpenDraft release, as its opendraft-update.properties says. Every GitHub release has that file next to
 * the jar, and GitHub's /releases/latest/download/ link always points at the newest release's copy (never a draft or a
 * pre-release). That's an ordinary download, so it isn't held to the API's limit of 60 requests an hour per address,
 * which a whole college network would share.
 *
 * <p>The file is signed with the release key ({@code ./gradlew releaseFiles}), and one that doesn't check out against
 * {@link #PUBLIC_KEY} is turned down. Otherwise whoever got into the GitHub account could put code on every player's
 * computer. No Minecraft classes here, so the standalone synchronizer can check too.
 */
public record Release(String version, String jar, String sha256) {
    public static final String REPO = "https://github.com/AB-TechGen/lockout-mc-mod";
    /** The page to get a release by hand from. */
    public static final String PAGE = REPO + "/releases/latest";
    private static final String DOWNLOADS = REPO + "/releases/latest/download/";
    /** The release key's public half. build.gradle's releaseFiles refuses to sign with any other key. */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAA3YVzigjSKkYuh8zi+FzmsYgiPIVqsiJ08R+5sClDxs=";
    /** Far bigger than the jar ever is, so a bad download can't fill the disk. */
    private static final long MAX_JAR = 64L << 20;
    private static final Pattern VERSION = Pattern.compile("[0-9][0-9A-Za-z.+-]{0,30}");
    private static final Pattern JAR = Pattern.compile("opendraft-[0-9A-Za-z.+-]{1,40}\\.jar");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub answers with a redirect to wherever the file is kept
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** The text that's signed. build.gradle's releaseFiles builds the same. */
    static String signed(String version, String jar, String sha256) {
        return "opendraft-update\n" + version + "\n" + jar + "\n" + sha256 + "\n";
    }

    /** The newest release, checked against the release key. */
    public static Release latest() throws IOException {
        HttpResponse<String> r = get(DOWNLOADS + "opendraft-update.properties", Duration.ofSeconds(15), HttpResponse.BodyHandlers.ofString());
        return parse(r.body());
    }

    static Release parse(String text) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        String version = p.getProperty("version", "").trim();
        String jar = p.getProperty("jar", "").trim();
        String sha = p.getProperty("sha256", "").trim();
        String signature = p.getProperty("signature", "").trim();
        if (!VERSION.matcher(version).matches() || !JAR.matcher(jar).matches() || !SHA.matcher(sha).matches()) {
            throw new IOException("the release's update file isn't in the right form");
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            Signature check = Signature.getInstance("Ed25519");
            check.initVerify(key);
            check.update(signed(version, jar, sha).getBytes(StandardCharsets.UTF_8));
            if (!check.verify(Base64.getDecoder().decode(signature))) throw new IOException("the release isn't signed with the OpenDraft release key");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("the release isn't signed with the OpenDraft release key");
        }
        return new Release(version, jar, sha);
    }

    /** Whether this release is newer than {@code current}. */
    public boolean newerThan(String current) {
        return OpenDraftHost.compareVersions(version, current) > 0;
    }

    /**
     * Downloads the jar to {@code to}, which only appears once the whole file is there and matches the signed checksum.
     * {@code progress} gets the bytes so far.
     */
    public void download(Path to, LongConsumer progress) throws IOException {
        HttpResponse<InputStream> r = get(DOWNLOADS + jar, Duration.ofMinutes(10), HttpResponse.BodyHandlers.ofInputStream());
        Path part = to.resolveSibling(to.getFileName() + ".part");
        MessageDigest digest = newDigest();
        long total = 0;
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_JAR) throw new IOException("the download is far bigger than OpenDraft is");
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
                progress.accept(total);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException("the download doesn't match the release (a newer one may have just come out; try again)");
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Whether {@code file} is this release's jar, whole. */
    public boolean matches(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = newDigest();
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) digest.update(buf, 0, n);
            return HexFormat.of().formatHex(digest.digest()).equals(sha256);
        } catch (IOException e) {
            return false;
        }
    }

    private static <T> HttpResponse<T> get(String url, Duration timeout, HttpResponse.BodyHandler<T> body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent", "OpenDraft").GET().build();
        HttpResponse<T> r;
        try {
            r = HTTP.send(request, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (r.statusCode() != 200) {
            if (r.body() instanceof InputStream in) in.close();
            throw new IOException(r.statusCode() == 404 ? "the latest release has no update file" : "GitHub answered " + r.statusCode());
        }
        return r;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
