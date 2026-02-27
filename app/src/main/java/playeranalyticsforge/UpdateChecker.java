package playeranalyticsforge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {
    private static final String RELEASES_URL = "https://api.github.com/repos/ilIger-518/PlayerAnalyticsForge/releases/latest";
    private static final Pattern TAG_PATTERN = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Object LOCK = new Object();
    private static ScheduledExecutorService executor;

    private UpdateChecker() {
    }

    public static void start() {
        if (!AnalyticsConfig.UPDATE_CHECK_ENABLED.get()) {
            return;
        }

        synchronized (LOCK) {
            if (executor != null) {
                return;
            }
            int intervalHours = AnalyticsConfig.UPDATE_CHECK_INTERVAL_HOURS.get();
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "playeranalytics-update-checker");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(UpdateChecker::checkForUpdates, 5, intervalHours * 3600L, TimeUnit.SECONDS);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    private static void checkForUpdates() {
        try {
            String latest = fetchLatestVersion();
            if (latest == null || latest.isBlank()) {
                PlayeranalyticsForgeMod.LOGGER.warn("Update check failed: could not determine latest version");
                return;
            }

            String current = PlayeranalyticsForgeMod.MOD_VERSION;
            int compare = compareVersions(current, latest);
            if (compare < 0) {
                PlayeranalyticsForgeMod.LOGGER.info("Update available: {} (current {})", latest, current);
            } else {
                PlayeranalyticsForgeMod.LOGGER.debug("Update check: current version {} is up to date", current);
            }
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.warn("Update check failed", ex);
        }
    }

    private static String fetchLatestVersion() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "PlayerAnalyticsForge");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        int code = connection.getResponseCode();
        if (code != 200) {
            PlayeranalyticsForgeMod.LOGGER.warn("Update check failed: HTTP {}", code);
            return null;
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }

        Matcher matcher = TAG_PATTERN.matcher(body.toString());
        if (matcher.find()) {
            return normalizeVersion(matcher.group(1));
        }
        return null;
    }

    private static String normalizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.replaceAll("[^0-9.].*", "");
    }

    private static int compareVersions(String current, String latest) {
        List<Integer> currentParts = parseVersion(current);
        List<Integer> latestParts = parseVersion(latest);
        int max = Math.max(currentParts.size(), latestParts.size());
        for (int i = 0; i < max; i++) {
            int c = i < currentParts.size() ? currentParts.get(i) : 0;
            int l = i < latestParts.size() ? latestParts.get(i) : 0;
            if (c != l) {
                return Integer.compare(c, l);
            }
        }
        return 0;
    }

    private static List<Integer> parseVersion(String version) {
        List<Integer> parts = new ArrayList<>();
        if (version == null || version.isBlank()) {
            return parts;
        }
        String[] tokens = version.split("\\.");
        for (String token : tokens) {
            String digits = token.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                parts.add(0);
            } else {
                try {
                    parts.add(Integer.parseInt(digits));
                } catch (NumberFormatException ex) {
                    parts.add(0);
                }
            }
        }
        return parts;
    }
}
