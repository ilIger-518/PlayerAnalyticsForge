package playeranalyticsforge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GeoIpUtil {
    private static final String API_URL = "http://ip-api.com/json/";
    private static final int TIMEOUT_MS = 5000;
    
    // Regex patterns for JSON parsing (avoiding full JSON library)
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("\"country\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern COUNTRY_CODE_PATTERN = Pattern.compile("\"countryCode\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REGION_PATTERN = Pattern.compile("\"regionName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CITY_PATTERN = Pattern.compile("\"city\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern LAT_PATTERN = Pattern.compile("\"lat\"\\s*:\\s*([0-9.-]+)");
    private static final Pattern LON_PATTERN = Pattern.compile("\"lon\"\\s*:\\s*([0-9.-]+)");

    private GeoIpUtil() {
    }

    public static CompletableFuture<GeoIpData> lookupAsync(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> lookup(ipAddress));
    }

    public static GeoIpData lookup(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty() || isPrivateIp(ipAddress)) {
            return new GeoIpData(null, null, null, null, null, null);
        }

        try {
            URL url = new URL(API_URL + ipAddress);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "PlayerAnalyticsForge/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                PlayeranalyticsForgeMod.LOGGER.debug("GeoIP lookup failed with HTTP {}", responseCode);
                return new GeoIpData(null, null, null, null, null, null);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            return parseGeoIpResponse(response.toString());
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.debug("GeoIP lookup failed for {}: {}", ipAddress, ex.getMessage());
            return new GeoIpData(null, null, null, null, null, null);
        }
    }

    private static GeoIpData parseGeoIpResponse(String json) {
        String country = extractPattern(COUNTRY_PATTERN, json);
        String countryCode = extractPattern(COUNTRY_CODE_PATTERN, json);
        String region = extractPattern(REGION_PATTERN, json);
        String city = extractPattern(CITY_PATTERN, json);
        
        Double latitude = null;
        Double longitude = null;
        try {
            String latStr = extractPattern(LAT_PATTERN, json);
            String lonStr = extractPattern(LON_PATTERN, json);
            if (latStr != null && lonStr != null) {
                latitude = Double.parseDouble(latStr);
                longitude = Double.parseDouble(lonStr);
            }
        } catch (NumberFormatException ex) {
            // Ignore
        }

        return new GeoIpData(country, countryCode, region, city, latitude, longitude);
    }

    private static String extractPattern(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static boolean isPrivateIp(String ip) {
        if (ip.startsWith("127.") || ip.equals("localhost") || ip.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
            return true;
        }
        return false;
    }

    public static final class GeoIpData {
        public final String country;
        public final String countryCode;
        public final String region;
        public final String city;
        public final Double latitude;
        public final Double longitude;

        public GeoIpData(String country, String countryCode, String region, String city, 
                         Double latitude, Double longitude) {
            this.country = country;
            this.countryCode = countryCode;
            this.region = region;
            this.city = city;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public boolean hasData() {
            return country != null || city != null;
        }
    }
}
