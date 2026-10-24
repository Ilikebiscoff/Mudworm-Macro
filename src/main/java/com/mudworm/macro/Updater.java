package com.mudworm.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Semi-automatic updater. Looks at the {@code latest-build} release of the GitHub repo, tells you in chat when
 * a newer jar exists, and {@code /mudworm update} downloads it into the mods folder (hash-checked against the
 * release digest). The old jar is removed when the game closes, so the next launch runs the new code.
 * Nothing downloaded is ever executed here.
 */
public final class Updater {
    private Updater() {}

    private static final String MOD_ID = "mudwormmacro";
    private static final String API = "https://api.github.com/repos/Ilikebiscoff/Mudworm-Macro/releases/tags/latest-build";
    private static final Pattern ASSET = Pattern.compile("^mudworm-macro-(\\d+)\\.(\\d+)\\.(\\d+)\\.jar$");
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static boolean checkedOnJoin, deleteScheduled;

    private record Latest(String name, int build, long size, String digest, String url) {}
    private static volatile Latest latest;

    // ---- public entry points --------------------------------------------------------------------

    /**
     * At game start: delete the older mudworm-macro jars (and half-downloaded files) next to the running one. After an
     * update restart the old jar is no longer loaded, so it is not locked any more and can simply be removed.
     */
    public static void cleanOld() {
        Path own = ownJar();
        if (own == null) return;
        int mine = buildOf(currentVersion());
        Path dir = own.toAbsolutePath().getParent();
        if (dir == null || mine < 0) return;
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (f.equals(own.toAbsolutePath()) || n.equals(own.getFileName().toString())) continue;
                Matcher m = ASSET.matcher(n);
                boolean old = m.matches() && Integer.parseInt(m.group(3)) < mine;
                boolean part = n.startsWith("mudworm-macro-") && n.endsWith(".jar.part");
                if (!old && !part) continue;
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // still locked: the next start tries again
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // nothing to clean
        }
    }

    /** First world join of this launch: quiet check, only speaks up if there is something newer. */
    public static void onJoin() {
        if (checkedOnJoin) return;
        checkedOnJoin = true;
        run(true, false);
    }

    /** {@code download=false}: just report. {@code quiet}: say nothing unless an update exists. */
    public static void run(boolean quiet, boolean download) {
        if (!busy.compareAndSet(false, true)) {
            if (!quiet) say("Already checking/downloading, one moment.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                work(quiet, download);
            } catch (Exception e) {
                if (!quiet) say("Update failed: " + e);
            } finally {
                busy.set(false);
            }
        }, "mudworm-updater");
        t.setDaemon(true);
        t.start();
    }

    public static String versionInfo() {
        Latest l = latest;
        return "Installed: " + currentVersion() + (l == null ? " (latest not checked yet: /mudworm update check)"
                : ", latest build: " + l.name());
    }

    // ---- internals -------------------------------------------------------------------------------------

    private static void work(boolean quiet, boolean download) throws Exception {
        Path jar = ownJar();
        if (jar == null) {
            if (!quiet) say("Updater unavailable: the mod isn't running from a .jar in the mods folder.");
            return;
        }
        Latest l = fetchLatest();
        latest = l;
        int cur = buildOf(currentVersion());
        if (l.build() <= cur) {
            if (!quiet) say("Up to date (" + currentVersion() + ").");
            return;
        }
        if (!download) {
            say("Update available: " + l.name() + " (you have " + currentVersion() + "). Run /mudworm update to download it.");
            return;
        }
        say("Downloading " + l.name() + " ...");
        install(l, jar);
    }

    private static Latest fetchLatest() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "mudworm-macro-updater")
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IllegalStateException("GitHub answered HTTP " + r.statusCode());
        JsonArray assets = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("assets");
        for (JsonElement el : assets) {
            JsonObject a = el.getAsJsonObject();
            String name = a.get("name").getAsString();
            Matcher m = ASSET.matcher(name);
            if (!m.matches()) continue;
            String digest = a.has("digest") && !a.get("digest").isJsonNull() ? a.get("digest").getAsString() : null;
            return new Latest(name, Integer.parseInt(m.group(3)), a.get("size").getAsLong(), digest,
                    a.get("browser_download_url").getAsString());
        }
        throw new IllegalStateException("no mudworm-macro jar on the latest-build release");
    }

    private static void install(Latest l, Path currentJar) throws Exception {
        if (l.digest() == null || !l.digest().toLowerCase(Locale.ROOT).startsWith("sha256:"))
            throw new IllegalStateException("release has no SHA-256 digest, refusing to install unverified code");
        Path mods = currentJar.toAbsolutePath().getParent();
        Path fin = mods.resolve(l.name()), part = mods.resolve(l.name() + ".part");
        if (Files.exists(fin)) {
            scheduleDelete(currentJar);
            say(l.name() + " is already in your mods folder. Restart the game to use it.");
            return;
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(l.url()))
                .header("User-Agent", "mudworm-macro-updater")
                .timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            String host = r.uri().getHost();
            if (r.statusCode() != 200) throw new IllegalStateException("download answered HTTP " + r.statusCode());
            if (!"https".equals(r.uri().getScheme()) || host == null
                    || !(host.endsWith("github.com") || host.endsWith("githubusercontent.com")))
                throw new IllegalStateException("unexpected download host " + host);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IllegalStateException("download too large");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String got = HexFormat.of().formatHex(md.digest());
            String want = l.digest().substring("sha256:".length());
            if (total != l.size() || total < 10_000 || !got.equalsIgnoreCase(want))
                throw new IllegalStateException("downloaded file doesn't match the release (size/SHA-256), discarded");
            if (!isOurMod(part)) throw new IllegalStateException("downloaded file isn't this mod, discarded");
            Files.move(part, fin, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }
        scheduleDelete(currentJar);
        boolean gone = false;
        try {
            gone = Files.deleteIfExists(currentJar); // works when the OS does not lock the running jar
        } catch (IOException ignored) {
            // locked while the game runs
        }
        say("Downloaded " + l.name() + ". Restart the game to use it. "
                + (gone ? "The old jar is deleted."
                : "The old " + currentJar.getFileName() + " is deleted automatically when the game starts again."));
    }

    private static boolean isOurMod(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var e = z.getEntry("fabric.mod.json");
            if (e == null) return false;
            try (var rd = new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8)) {
                return MOD_ID.equals(JsonParser.parseReader(rd).getAsJsonObject().get("id").getAsString());
            }
        } catch (Exception ex) {
            return false;
        }
    }

    /** The old jar is locked while the game runs: a tiny detached helper deletes it after we have exited. */
    private static synchronized void scheduleDelete(Path old) {
        if (deleteScheduled) return;
        String p = old.toAbsolutePath().toString();
        if (p.contains("\"")) return; // can't quote it safely: user deletes it by hand
        deleteScheduled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                ProcessBuilder pb = win
                        ? new ProcessBuilder("cmd", "/c", "start", "/b", "cmd", "/c",
                                "ping -n 4 127.0.0.1 >nul & del \"" + p + "\"")
                        : new ProcessBuilder("sh", "-c", "(sleep 3; rm -f \"" + p + "\") >/dev/null 2>&1 &");
                pb.start();
            } catch (Exception ignored) {
                // nothing more we can do while the JVM is going down
            }
        }, "mudworm-updater-cleanup"));
    }

    private static ModContainer container() {
        return FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
    }

    public static String currentVersion() {
        ModContainer c = container();
        return c == null ? "unknown" : c.getMetadata().getVersion().getFriendlyString();
    }

    /** Build number = the last number of 1.0.N, or -1. */
    private static int buildOf(String version) {
        Matcher m = Pattern.compile("(\\d+)$").matcher(version);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Path of the running mod jar, or null when not started from a jar (dev environment). */
    private static Path ownJar() {
        ModContainer c = container();
        if (c == null || c.getOrigin().getKind() != ModOrigin.Kind.PATH) return null;
        for (Path p : c.getOrigin().getPaths()) {
            if (p.getFileName() != null && p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** Last thing the updater said (shown in the settings screen). */
    public static volatile String lastMessage = "";

    public static String shortVersion() {
        return "v" + currentVersion();
    }

    private static void say(String s) {
        lastMessage = s;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> Chat.msg(s));
    }
}
