package playeranalyticsforge;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class AnalyticsWebServer {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8804;
    private static final int DEFAULT_LIMIT = 50;
    private static final Object LOCK = new Object();
    private static HttpServer server;

    private AnalyticsWebServer() {
    }

    public static void start() {
        synchronized (LOCK) {
            if (server != null) {
                return;
            }

            try {
                server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
            } catch (IOException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to start analytics web server", ex);
                return;
            }

            server.createContext("/", new IndexHandler());
            server.createContext("/api/summary", exchange -> handleJson(exchange, PlayerAnalyticsDb.getSummaryJson()));
            server.createContext("/api/events", exchange -> handleJson(exchange, PlayerAnalyticsDb.getRecentEventsJson(readLimit(exchange))));
            server.createContext("/api/players", exchange -> handleJson(exchange, PlayerAnalyticsDb.getPlayersJson(readLimit(exchange))));
            server.createContext("/api/kills", exchange -> handleJson(exchange, PlayerAnalyticsDb.getKillDetailsJson(readLimit(exchange))));
            server.createContext("/api/sessions", exchange -> handleJson(exchange, PlayerAnalyticsDb.getSessionsJson(readLimit(exchange))));
            server.setExecutor(null);
            server.start();
            PlayeranalyticsForgeMod.LOGGER.info("Analytics web UI running at http://{}:{}", HOST, PORT);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (server != null) {
                server.stop(0);
                server = null;
                PlayeranalyticsForgeMod.LOGGER.info("Analytics web UI stopped");
            }
        }
    }

    private static void handleJson(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static int readLimit(HttpExchange exchange) {
        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String limitValue = params.get("limit");
        if (limitValue == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(limitValue);
            return Math.max(1, Math.min(limit, 500));
        } catch (NumberFormatException ex) {
            return DEFAULT_LIMIT;
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return params;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0 && idx < pair.length() - 1) {
                String key = decode(pair.substring(0, idx));
                String value = decode(pair.substring(idx + 1));
                params.put(key, value);
            }
        }
        return params;
    }

    private static String decode(String value) {
        return value.replace("+", " ");
    }

    private static final class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = INDEX_HTML.getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private static final String INDEX_HTML = """
        <!doctype html>
        <html lang=\"en\">
          <head>
            <meta charset=\"utf-8\" />
            <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
            <title>Playeranalytics</title>
            <style>
              :root {
                color-scheme: light;
                --bg: #f6f4ef;
                --ink: #1f1d1a;
                --muted: #6f655d;
                --accent: #d0752f;
                --card: #ffffff;
                --line: #e4ddd4;
              }
              body {
                margin: 0;
                font-family: "Space Grotesk", "IBM Plex Sans", "Segoe UI", sans-serif;
                background: radial-gradient(circle at top, #fff7ed 0%, var(--bg) 55%);
                color: var(--ink);
              }
              header {
                padding: 32px 24px 12px;
              }
              h1 {
                margin: 0 0 6px;
                font-size: 32px;
                letter-spacing: -0.02em;
              }
              p {
                margin: 0;
                color: var(--muted);
              }
              main {
                display: grid;
                gap: 18px;
                padding: 0 24px 32px;
                grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
              }
              .card {
                background: var(--card);
                border: 1px solid var(--line);
                border-radius: 16px;
                padding: 16px;
                box-shadow: 0 12px 30px rgba(0, 0, 0, 0.08);
              }
              .stat {
                font-size: 26px;
                font-weight: 600;
                margin-top: 8px;
              }
              table {
                width: 100%;
                border-collapse: collapse;
                font-size: 14px;
              }
              th, td {
                padding: 8px;
                border-bottom: 1px solid var(--line);
                text-align: left;
              }
              th {
                color: var(--muted);
                font-weight: 600;
              }
              .pill {
                display: inline-block;
                padding: 2px 8px;
                border-radius: 999px;
                background: rgba(208, 117, 47, 0.12);
                color: var(--accent);
                font-weight: 600;
              }
              .footer {
                padding: 0 24px 24px;
                color: var(--muted);
                font-size: 12px;
              }
            </style>
          </head>
          <body>
            <header>
              <h1>Playeranalytics</h1>
              <p>Local server dashboard. Refreshes automatically.</p>
            </header>
            <main>
              <section class=\"card\">
                <div class=\"pill\">Summary</div>
                <div class=\"stat\" id=\"summary-joins\">0 joins</div>
                <div class=\"stat\" id=\"summary-leaves\">0 leaves</div>
                <div class=\"stat\" id=\"summary-unique\">0 unique players</div>
                <p id=\"summary-playtime\" style=\"margin-top: 8px;\">Total playtime: 0h 0m</p>
                <p id=\"summary-sessions\">Sessions: 0 (avg 0s)</p>
                <p id=\"summary-last\">Last event: -</p>
              </section>
              <section class=\"card\">
                <div class=\"pill\">Recent events</div>
                <table>
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Event</th>
                      <th>Time (UTC)</th>
                    </tr>
                  </thead>
                  <tbody id=\"events-body\"></tbody>
                </table>
              </section>
              <section class=\"card\">
                <div class=\"pill\">Players</div>
                <table>
                  <thead>
                    <tr>
                      <th>Player</th>                      <th>Playtime</th>                      <th>Last seen</th>
                      <th>Joins</th>
                      <th>Leaves</th>
                      <th>Kills</th>
                      <th>Deaths</th>
                      <th>K/D</th>
                    </tr>
                  </thead>
                  <tbody id=\"players-body\"></tbody>
                </table>
              </section>
              <section class=\"card\">
                <div class=\"pill\">Recent Sessions</div>
                <table>
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Duration</th>
                      <th>Start (UTC)</th>
                      <th>End (UTC)</th>
                    </tr>
                  </thead>
                  <tbody id=\"sessions-body\"></tbody>
                </table>
              </section>              <section class="card">
                <div class="pill">Kill Details</div>
                <table>
                  <thead>
                    <tr>
                      <th>Killer</th>
                      <th>Victim</th>
                      <th>Count</th>
                      <th>Last Kill (UTC)</th>
                    </tr>
                  </thead>
                  <tbody id="kills-body"></tbody>
                </table>
              </section>            </main>
            <div class=\"footer\">Powered by Playeranalytics Forge mod</div>
            <script>
              async function loadSummary() {
                const res = await fetch("/api/summary");
                const data = await res.json();
                document.getElementById("summary-joins").textContent = `${data.joins} joins`;
                document.getElementById("summary-leaves").textContent = `${data.leaves} leaves`;
                document.getElementById("summary-unique").textContent = `${data.uniquePlayers} unique players`;
                const playtimeHours = Math.floor(data.totalPlaytimeSeconds / 3600);
                const playtimeMinutes = Math.floor((data.totalPlaytimeSeconds % 3600) / 60);
                document.getElementById("summary-playtime").textContent = `Total playtime: ${playtimeHours}h ${playtimeMinutes}m`;
                const avgMinutes = Math.floor(data.avgSessionDuration / 60);
                const avgSeconds = data.avgSessionDuration % 60;
                document.getElementById("summary-sessions").textContent = `Sessions: ${data.totalSessions} (avg ${avgMinutes}m ${avgSeconds}s)`;
                document.getElementById("summary-last").textContent = `Last event: ${data.lastEvent ?? "-"}`;
              }

              async function loadEvents() {
                const res = await fetch("/api/events?limit=25");
                const data = await res.json();
                const body = document.getElementById("events-body");
                body.innerHTML = "";
                data.forEach(event => {
                  const row = document.createElement("tr");
                  row.innerHTML = `<td>${event.playerName}</td><td>${event.eventType}</td><td>${event.eventTimeUtc}</td>`;
                  body.appendChild(row);
                });
              }

              async function loadPlayers() {
                const res = await fetch("/api/players?limit=25");
                const data = await res.json();
                const body = document.getElementById("players-body");
                body.innerHTML = "";
                data.forEach(player => {
                  const row = document.createElement("tr");
                  const playtimeHours = Math.floor(player.totalPlaytimeSeconds / 3600);
                  const playtimeMinutes = Math.floor((player.totalPlaytimeSeconds % 3600) / 60);
                  const playtimeDisplay = `${playtimeHours}h ${playtimeMinutes}m`;
                  row.innerHTML = `<td>${player.playerName}</td><td>${playtimeDisplay}</td><td>${player.lastSeen ?? "-"}</td><td>${player.joins}</td><td>${player.leaves}</td><td>${player.kills}</td><td>${player.deaths}</td><td>${player.kdRatio}</td>`;
                  body.appendChild(row);
                });
              }

              async function loadKills() {
                const res = await fetch("/api/kills?limit=50");
                const data = await res.json();
                const body = document.getElementById("kills-body");
                body.innerHTML = "";
                data.forEach(kill => {
                  const row = document.createElement("tr");
                  const victimDisplay = kill.victimName ? `${kill.victimName} (${kill.victimType})` : kill.victimType;
                  row.innerHTML = `<td>${kill.killerName}</td><td>${victimDisplay}</td><td>${kill.killCount}</td><td>${kill.lastKillTime}</td>`;
                  body.appendChild(row);
                });
              }

              async function loadSessions() {
                const res = await fetch("/api/sessions?limit=25");
                const data = await res.json();
                const body = document.getElementById("sessions-body");
                body.innerHTML = "";
                data.forEach(session => {
                  const row = document.createElement("tr");
                  const durationMinutes = Math.floor(session.durationSeconds / 60);
                  const durationSeconds = session.durationSeconds % 60;
                  const durationDisplay = `${durationMinutes}m ${durationSeconds}s`;
                  row.innerHTML = `<td>${session.playerName}</td><td>${durationDisplay}</td><td>${session.sessionStart}</td><td>${session.sessionEnd}</td>`;
                  body.appendChild(row);
                });
              }

              async function refreshAll() {
                await Promise.all([loadSummary(), loadEvents(), loadPlayers(), loadSessions(), loadKills()]);
              }

              refreshAll();
              setInterval(refreshAll, 5000);
            </script>
          </body>
        </html>
        """;
}
