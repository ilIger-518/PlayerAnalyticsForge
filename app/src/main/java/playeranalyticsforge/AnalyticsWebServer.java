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
            
            // Check if web server is enabled in config
            if (!AnalyticsConfig.WEB_SERVER_ENABLED.get()) {
                PlayeranalyticsForgeMod.LOGGER.info("Analytics web server is disabled in config");
                return;
            }
            
            String host = AnalyticsConfig.WEB_SERVER_HOST.get();
            int port = AnalyticsConfig.WEB_SERVER_PORT.get();

            try {
                server = HttpServer.create(new InetSocketAddress(host, port), 0);
            } catch (IOException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to start analytics web server on {}:{}", host, port, ex);
                return;
            }

            server.createContext("/", new IndexHandler());
            server.createContext("/api/summary", exchange -> handleJson(exchange, PlayerAnalyticsDb.getSummaryJson()));
            server.createContext("/api/events", exchange -> handleJson(exchange, PlayerAnalyticsDb.getRecentEventsJson(readLimit(exchange))));
            server.createContext("/api/players", exchange -> handleJson(exchange, PlayerAnalyticsDb.getPlayersJson(readLimit(exchange))));
            server.createContext("/api/kills", exchange -> handleJson(exchange, PlayerAnalyticsDb.getKillDetailsJson(readLimit(exchange))));
            server.createContext("/api/sessions", exchange -> handleJson(exchange, PlayerAnalyticsDb.getSessionsJson(readLimit(exchange))));
            server.createContext("/api/playtime", exchange -> handleJson(exchange, PlayerAnalyticsDb.getPlaytimeDetailsJson()));
            server.createContext("/api/metrics", exchange -> handleJson(exchange, PlayerAnalyticsDb.getServerMetricsJson()));
            server.createContext("/api/metrics/history", exchange -> handleJson(exchange, PlayerAnalyticsDb.getMetricsHistoryJson(readLimit(exchange))));
            server.createContext("/api/combat", exchange -> handleJson(exchange, PlayerAnalyticsDb.getCombatStatsJson()));
            server.createContext("/api/weapons", exchange -> handleJson(exchange, PlayerAnalyticsDb.getWeaponStatsJson()));
            server.createContext("/api/combat/deaths/", new DeathCausesHandler());
            server.createContext("/api/combat/matrix/", new KillMatrixHandler());
            server.createContext("/api/activity/trends", exchange -> handleJson(exchange, PlayerAnalyticsDb.getActivityTrendsJson(readLimit(exchange))));
            server.createContext("/api/activity/hourly", exchange -> handleJson(exchange, PlayerAnalyticsDb.getHourlyActivityJson()));
            server.createContext("/api/sessions/insights", exchange -> handleJson(exchange, PlayerAnalyticsDb.getSessionInsightsJson()));
            server.createContext("/api/players/online", exchange -> handleJson(exchange, PlayerAnalyticsDb.getOnlinePlayersJson()));
            server.createContext("/api/worlds", exchange -> handleJson(exchange, PlayerAnalyticsDb.getWorldDistributionJson()));
            server.createContext("/api/world/", new WorldHandler());
            server.createContext("/api/leaderboard/", new LeaderboardHandler());
            server.createContext("/api/player/", new PlayerHandler());
            server.createContext("/player/", new PlayerPageHandler());
            server.setExecutor(null);
            server.start();
            PlayeranalyticsForgeMod.LOGGER.info("Analytics web UI running at http://{}:{}", host, port);
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
            <script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>
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
              .card-full {
                grid-column: 1 / -1;
              }
              .card-half {
                grid-column: span 1;
              }
              @media (max-width: 1200px) {
                .card-half {
                  grid-column: 1 / -1;
                }
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
              th.sortable {
                cursor: pointer;
                user-select: none;
                position: relative;
                padding-right: 24px;
              }
              th.sortable:hover {
                color: var(--accent);
              }
              th.sortable::after {
                content: '⇅';
                position: absolute;
                right: 8px;
                opacity: 0.3;
                font-size: 12px;
              }
              th.sortable.asc::after {
                content: '▲';
                opacity: 1;
                color: var(--accent);
              }
              th.sortable.desc::after {
                content: '▼';
                opacity: 1;
                color: var(--accent);
              }
              .table-controls {
                display: flex;
                gap: 12px;
                align-items: center;
                margin-bottom: 12px;
                flex-wrap: wrap;
              }
              .search-input {
                flex: 1;
                min-width: 200px;
                padding: 8px 12px;
                border: 1px solid var(--line);
                border-radius: 6px;
                background: var(--bg);
                color: var(--text);
                font-size: 14px;
              }
              .search-input:focus {
                outline: none;
                border-color: var(--accent);
              }
              .filter-badge {
                display: inline-block;
                padding: 4px 8px;
                background: rgba(208, 117, 47, 0.15);
                border-radius: 4px;
                font-size: 12px;
                color: var(--accent);
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
              .overview {
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                color: white;
                border: none;
                box-shadow: 0 20px 60px rgba(102, 126, 234, 0.3);
              }
              .overview-grid {
                display: grid;
                gap: 16px;
                grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                margin-top: 16px;
              }
              .overview-metric {
                background: rgba(255, 255, 255, 0.15);
                border-radius: 12px;
                padding: 16px;
                backdrop-filter: blur(10px);
              }
              .overview-metric-label {
                font-size: 12px;
                opacity: 0.9;
                text-transform: uppercase;
                letter-spacing: 0.5px;
                margin-bottom: 8px;
              }
              .overview-metric-value {
                font-size: 28px;
                font-weight: 700;
                line-height: 1;
              }
              .overview-metric-sub {
                font-size: 13px;
                opacity: 0.8;
                margin-top: 4px;
              }
              .status-indicator {
                display: inline-block;
                width: 10px;
                height: 10px;
                border-radius: 50%;
                margin-right: 6px;
                animation: pulse 2s ease-in-out infinite;
              }
              .status-online {
                background: #10b981;
                box-shadow: 0 0 10px #10b981;
              }
              @keyframes pulse {
                0%, 100% { opacity: 1; }
                50% { opacity: 0.5; }
              }
            </style>
          </head>
          <body>
            <header>
              <h1>Playeranalytics</h1>
              <p>Local server dashboard. Refreshes automatically.</p>
            </header>
            <main>
              <section class=\"card card-full overview\">
                <div style=\"display: flex; align-items: center; margin-bottom: 4px;\">
                  <span class=\"status-indicator status-online\"></span>
                  <h2 style=\"margin: 0; font-size: 24px; font-weight: 600;\">Server Overview</h2>
                </div>
                <p style=\"opacity: 0.9; margin-bottom: 16px;\">Real-time server health and statistics</p>
                <div class=\"overview-grid\">
                  <div class=\"overview-metric\">
                    <div class=\"overview-metric-label\">Server Performance</div>
                    <div class=\"overview-metric-value\" id=\"overview-tps\">20.0</div>
                    <div class=\"overview-metric-sub\" id=\"overview-tps-status\">TPS (Excellent)</div>
                  </div>
                  <div class=\"overview-metric\">
                    <div class=\"overview-metric-label\">Memory Usage</div>
                    <div class=\"overview-metric-value\" id=\"overview-ram\">0 MB</div>
                    <div class=\"overview-metric-sub\" id=\"overview-ram-percent\">0% utilized</div>
                  </div>
                  <div class=\"overview-metric\">
                    <div class=\"overview-metric-label\">Online Players</div>
                    <div class=\"overview-metric-value\" id=\"overview-online\">0</div>
                    <div class=\"overview-metric-sub\" id=\"overview-total-players\">0 total players</div>
                  </div>
                  <div class=\"overview-metric\">
                    <div class=\"overview-metric-label\">Recent Activity</div>
                    <div class=\"overview-metric-value\" id=\"overview-activity\">0</div>
                    <div class=\"overview-metric-sub\">events today</div>
                  </div>
                  <div class=\"overview-metric\">
                    <div class=\"overview-metric-label\">Total Playtime</div>
                    <div class=\"overview-metric-value\" id=\"overview-playtime\">0h</div>
                    <div class=\"overview-metric-sub\" id=\"overview-sessions\">0 sessions</div>
                  </div>
                  <div class=\"overview-metric\">
                    <div class=\"overview-metric-label\">Combat Stats</div>
                    <div class=\"overview-metric-value\" id=\"overview-kills\">0</div>
                    <div class=\"overview-metric-sub\" id=\"overview-deaths\">0 deaths</div>
                  </div>
                </div>
              </section>
              <section class=\"card\">
                <div class=\"pill\">Summary</div>
                <div class=\"stat\" id=\"summary-joins\">0 joins</div>
                <div class=\"stat\" id=\"summary-leaves\">0 leaves</div>
                <div class=\"stat\" id=\"summary-unique\">0 unique players</div>
                <p id=\"summary-playtime\" style=\"margin-top: 8px;\">Total playtime: 0h 0m</p>
                <p id=\"summary-sessions\">Sessions: 0 (avg 0s)</p>
                <p id=\"summary-last\">Last event: -</p>
              </section>
              <section class=\"card card-half\">
                <div class=\"pill\">Online Players (<span id=\"online-count\">0</span>)</div>
                <div id=\"online-players-list\" style=\"font-size: 14px; margin-top: 12px; max-height: 220px; overflow-y: auto;\">
                  <p style=\"color: var(--muted);\">No players online</p>
                </div>
              </section>
              <section class=\"card card-half\">
                <div class=\"pill\">Playtime Distribution</div>
                <div style=\"position: relative; height: 200px;\">
                  <canvas id=\"playtimeChart\"></canvas>
                </div>
              </section>
              <section class=\"card card-half\">
                <div class=\"pill\">Top Players by Kills</div>
                <div style=\"position: relative; height: 200px;\">
                  <canvas id=\"killsChart\"></canvas>
                </div>
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
                <div class=\"table-controls\">
                  <input type=\"text\" id=\"search-sessions\" class=\"search-input\" placeholder=\"Search sessions...\">
                  <span id=\"search-sessions-count\" class=\"filter-badge\">0 results</span>
                </div>
                <table id=\"sessions-table\">
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
              </section>
              <section class=\"card card-half\">
                <div class=\"pill\">Server Performance (Last Hour)</div>
                <div style=\"font-size: 13px;\">
                  <p>Avg TPS: <strong id=\"metrics-avg-tps\">-</strong> / 20</p>
                  <p>Avg RAM: <strong id=\"metrics-avg-ram\">-</strong> MB</p>
                  <p>Avg CPU: <strong id=\"metrics-avg-cpu\">-</strong>%</p>
                  <p>Avg Entities: <strong id=\"metrics-avg-entities\">-</strong></p>
                </div>
              </section>
              <section class=\"card\">
                <div class=\"pill\">Playtime Analysis</div>
                <div class=\"table-controls\">
                  <input type=\"text\" id=\"search-playtime\" class=\"search-input\" placeholder=\"Search playtime...\">
                  <span id=\"search-playtime-count\" class=\"filter-badge\">0 results</span>
                </div>
                <table id=\"playtime-table\">
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Total Playtime</th>
                      <th>Active Playtime</th>
                      <th>AFK Time</th>
                      <th>AFK Periods</th>
                    </tr>
                  </thead>
                  <tbody id=\"playtime-body\"></tbody>
                </table>
              </section>              <section class=\"card card-full\">
                <div class=\"pill\">Combat Statistics</div>
                <div class=\"table-controls\">
                  <input type=\"text\" id=\"search-combat\" class=\"search-input\" placeholder=\"Search combat stats...\">
                  <span id=\"search-combat-count\" class=\"filter-badge\">0 results</span>
                </div>
                <table id=\"combat-table\">
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Total Kills</th>
                      <th>PvP Kills</th>
                      <th>PvE Kills</th>
                      <th>PvP Ratio</th>
                      <th>Deaths</th>
                      <th>K/D Ratio</th>
                      <th>Kill Streak</th>
                      <th>Max Streak</th>
                    </tr>
                  </thead>
                  <tbody id=\"combat-body\"></tbody>
                </table>
              </section>
              <section class=\"card card-half\">
                <div class=\"pill\">Weapon Usage</div>
                <div style=\"position: relative; height: 250px;\">
                  <canvas id=\"weaponChart\"></canvas>
                </div>
              </section>
              <section class=\"card card-half\">
                <div class=\"pill\">Top Kill Streaks</div>
                <table>
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Current</th>
                      <th>Record</th>
                    </tr>
                  </thead>
                  <tbody id=\"streaks-body\"></tbody>
                </table>
              </section>
              <section class="card">
                <div class="pill">Kill Details</div>
                <div class="table-controls">
                  <input type="text" id="search-kills" class="search-input" placeholder="Search kills...">
                  <span id="search-kills-count" class="filter-badge">0 results</span>
                </div>
                <table id="kills-table">
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
              </section>
              <section class=\"card\">
                <div class=\"pill\">Session Insights</div>
                <div style=\"font-size: 13px;\">
                  <p>Total Sessions: <strong id=\"insights-total\">-</strong></p>
                  <p>Avg Duration: <strong id=\"insights-avg\">-</strong></p>
                  <p>Max Duration: <strong id=\"insights-max\">-</strong></p>
                  <p>Min Duration: <strong id=\"insights-min\">-</strong></p>
                </div>
              </section>
              <section class=\"card card-full\">
                <div class=\"pill\">Activity Trends (Last 30 Days)</div>
                <div style=\"position: relative; height: 300px;\">
                  <canvas id=\"activityTrendsChart\"></canvas>
                </div>
              </section>
              <section class=\"card card-full\">
                <div class=\"pill\">Hourly Activity (Peak Hours)</div>
                <div style=\"position: relative; height: 300px;\">
                  <canvas id=\"hourlyActivityChart\"></canvas>
                </div>
              </section>
              <section class=\"card card-full\">
                <div class=\"pill\">Leaderboards</div>
                <div style=\"display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));\">
                  <div>
                    <h3 style=\"margin: 8px 0; font-size: 16px; color: var(--accent);\">🏆 Most Active (Playtime)</h3>
                    <table style=\"margin-top: 8px;\">
                      <thead>
                        <tr>
                          <th>#</th>
                          <th>Player</th>
                          <th>Time</th>
                        </tr>
                      </thead>
                      <tbody id=\"leaderboard-playtime\"></tbody>
                    </table>
                  </div>
                  <div>
                    <h3 style=\"margin: 8px 0; font-size: 16px; color: var(--accent);\">⚔️ Highest K/D Ratio</h3>
                    <table style=\"margin-top: 8px;\">
                      <thead>
                        <tr>
                          <th>#</th>
                          <th>Player</th>
                          <th>K/D</th>
                        </tr>
                      </thead>
                      <tbody id=\"leaderboard-kd\"></tbody>
                    </table>
                  </div>
                  <div>
                    <h3 style=\"margin: 8px 0; font-size: 16px; color: var(--accent);\">🔥 Longest Kill Streak</h3>
                    <table style=\"margin-top: 8px;\">
                      <thead>
                        <tr>
                          <th>#</th>
                          <th>Player</th>
                          <th>Streak</th>
                        </tr>
                      </thead>
                      <tbody id=\"leaderboard-streak\"></tbody>
                    </table>
                  </div>
                  <div>
                    <h3 style=\"margin: 8px 0; font-size: 16px; color: var(--accent);\">💀 Total Kills</h3>
                    <table style=\"margin-top: 8px;\">
                      <thead>
                        <tr>
                          <th>#</th>
                          <th>Player</th>
                          <th>Kills</th>
                        </tr>
                      </thead>
                      <tbody id=\"leaderboard-kills\"></tbody>
                    </table>
                  </div>
                  <div>
                    <h3 style=\"margin: 8px 0; font-size: 16px; color: var(--accent);\">⚡ PvP Kills</h3>
                    <table style=\"margin-top: 8px;\">
                      <thead>
                        <tr>
                          <th>#</th>
                          <th>Player</th>
                          <th>PvP</th>
                        </tr>
                      </thead>
                      <tbody id=\"leaderboard-pvp\"></tbody>
                    </table>
                  </div>
                  <div>
                    <h3 style=\"margin: 8px 0; font-size: 16px; color: var(--accent);\">📊 Most Sessions</h3>
                    <table style=\"margin-top: 8px;\">
                      <thead>
                        <tr>
                          <th>#</th>
                          <th>Player</th>
                          <th>Sessions</th>
                        </tr>
                      </thead>
                      <tbody id=\"leaderboard-sessions\"></tbody>
                    </table>
                  </div>
                </div>
              </section>
            </main>
            <div class=\"footer\">Powered by Playeranalytics Forge mod</div>
            <script>
              // Table sorting utility
              function makeTableSortable(tableId) {
                const table = document.getElementById(tableId);
                if (!table) return;
                
                const tbody = table.querySelector('tbody');
                const thead = table.querySelector('thead');
                if (!thead || !tbody) return;
                
                const headers = thead.querySelectorAll('th');
                headers.forEach((header, index) => {
                  // Skip rank columns (#)
                  if (header.textContent.trim() === '#') return;
                  
                  header.classList.add('sortable');
                  header.dataset.column = index;
                  header.dataset.order = 'none';
                  
                  header.addEventListener('click', () => {
                    const rows = Array.from(tbody.querySelectorAll('tr'));
                    const currentOrder = header.dataset.order;
                    const newOrder = currentOrder === 'asc' ? 'desc' : 'asc';
                    
                    // Remove sorting classes from all headers
                    headers.forEach(h => {
                      h.classList.remove('asc', 'desc');
                      if (h !== header) h.dataset.order = 'none';
                    });
                    
                    // Add sorting class to current header
                    header.classList.add(newOrder);
                    header.dataset.order = newOrder;
                    
                    // Sort rows
                    rows.sort((a, b) => {
                      const aCell = a.cells[index];
                      const bCell = b.cells[index];
                      if (!aCell || !bCell) return 0;
                      
                      let aValue = aCell.textContent.trim();
                      let bValue = bCell.textContent.trim();
                      
                      // Try to parse as number
                      const aNum = parseFloat(aValue.replace(/[^0-9.-]/g, ''));
                      const bNum = parseFloat(bValue.replace(/[^0-9.-]/g, ''));
                      
                      if (!isNaN(aNum) && !isNaN(bNum)) {
                        return newOrder === 'asc' ? aNum - bNum : bNum - aNum;
                      }
                      
                      // String comparison
                      return newOrder === 'asc' ? 
                        aValue.localeCompare(bValue) : 
                        bValue.localeCompare(aValue);
                    });
                    
                    // Re-append sorted rows
                    rows.forEach(row => tbody.appendChild(row));
                  });
                });
              }
              
              // Table filtering utility
              function addTableSearch(tableId, searchInputId) {
                const table = document.getElementById(tableId);
                const searchInput = document.getElementById(searchInputId);
                if (!table || !searchInput) return;
                
                const tbody = table.querySelector('tbody');
                if (!tbody) return;
                
                searchInput.addEventListener('input', (e) => {
                  const searchTerm = e.target.value.toLowerCase();
                  const rows = tbody.querySelectorAll('tr');
                  
                  let visibleCount = 0;
                  rows.forEach(row => {
                    const text = row.textContent.toLowerCase();
                    if (text.includes(searchTerm)) {
                      row.style.display = '';
                      visibleCount++;
                    } else {
                      row.style.display = 'none';
                    }
                  });
                  
                  // Update count badge if exists
                  const badge = document.getElementById(`${searchInputId}-count`);
                  if (badge) {
                    badge.textContent = `${visibleCount} results`;
                  }
                });
              }
              
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
                  const playerLink = `<a href=\"/player/${player.playerUuid}\" style=\"color: var(--accent); text-decoration: none;\">${player.playerName}</a>`;
                  row.innerHTML = `<td>${playerLink}</td><td>${playtimeDisplay}</td><td>${player.lastSeen ?? \"-\"}</td><td>${player.joins}</td><td>${player.leaves}</td><td>${player.kills}</td><td>${player.deaths}</td><td>${player.kdRatio}</td>`;
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
              async function loadPlaytimeDetails() {
                const res = await fetch(\"/api/playtime\");
                const data = await res.json();
                const body = document.getElementById(\"playtime-body\");
                body.innerHTML = \"\";
                data.forEach(player => {
                  const row = document.createElement(\"tr\");
                  const totalHours = Math.floor(player.totalPlaytimeSeconds / 3600);
                  const totalMinutes = Math.floor((player.totalPlaytimeSeconds % 3600) / 60);
                  const activeHours = Math.floor(player.activePlaytimeSeconds / 3600);
                  const activeMinutes = Math.floor((player.activePlaytimeSeconds % 3600) / 60);
                  const afkHours = Math.floor(player.totalAfkSeconds / 3600);
                  const afkMinutes = Math.floor((player.totalAfkSeconds % 3600) / 60);
                  const totalDisplay = `${totalHours}h ${totalMinutes}m`;
                  const activeDisplay = `${activeHours}h ${activeMinutes}m`;
                  const afkDisplay = `${afkHours}h ${afkMinutes}m`;
                  row.innerHTML = `<td>${player.playerName}</td><td>${totalDisplay}</td><td>${activeDisplay}</td><td>${afkDisplay}</td><td>${player.afkPeriods}</td>`;
                  body.appendChild(row);
                });
              }

              async function loadServerMetrics() {
                const res = await fetch(\"/api/metrics\");
                const data = await res.json();
                document.getElementById(\"metrics-avg-tps\").textContent = data.avgTps.toFixed(1);
                document.getElementById(\"metrics-avg-ram\").textContent = Math.round(data.avgRamUsedMb);
                document.getElementById(\"metrics-avg-cpu\").textContent = data.avgCpuUsage.toFixed(1);
                document.getElementById(\"metrics-avg-entities\").textContent = Math.round(data.avgEntities);
              }

              let playtimeChartInstance = null;
              let killsChartInstance = null;

              async function loadPlaytimeChart() {
                const res = await fetch(\"/api/playtime\");
                const data = await res.json();
                if (data.length === 0) return;

                // Sort by total playtime and take top 5
                const topPlayers = data.sort((a, b) => b.totalPlaytimeSeconds - a.totalPlaytimeSeconds).slice(0, 5);

                const ctx = document.getElementById(\"playtimeChart\").getContext(\"2d\");
                if (playtimeChartInstance) {
                  playtimeChartInstance.destroy();
                }
                playtimeChartInstance = new Chart(ctx, {
                  type: \"pie\",
                  data: {
                    labels: topPlayers.map(p => p.playerName),
                    datasets: [{
                      data: topPlayers.map(p => Math.floor(p.totalPlaytimeSeconds / 60)), // Convert to minutes for readability
                      backgroundColor: [\"#FF6384\", \"#36A2EB\", \"#FFCE56\", \"#4BC0C0\", \"#FF9F40\"],
                      borderColor: \"#ffffff\",
                      borderWidth: 2
                    }]
                  },
                  options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                      legend: {
                        position: \"bottom\"
                      }
                    }
                  }
                });
              }

              async function loadKillsChart() {
                const res = await fetch(\"/api/players?limit=10\");
                const data = await res.json();
                if (data.length === 0) return;

                // Sort by kills and take top 5
                const topKillers = data.sort((a, b) => b.kills - a.kills).slice(0, 5);

                const ctx = document.getElementById(\"killsChart\").getContext(\"2d\");
                if (killsChartInstance) {
                  killsChartInstance.destroy();
                }
                killsChartInstance = new Chart(ctx, {
                  type: \"bar\",
                  data: {
                    labels: topKillers.map(p => p.playerName),
                    datasets: [{
                      label: \"Kills\",
                      data: topKillers.map(p => p.kills),
                      backgroundColor: \"#36A2EB\",
                      borderColor: \"#36A2EB\",
                      borderWidth: 1
                    }]
                  },
                  options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    indexAxis: \"y\",
                    plugins: {
                      legend: {
                        display: false
                      }
                    },
                    scales: {
                      x: {
                        beginAtZero: true
                      }
                    }
                  }
                });
              }

              let weaponChartInstance = null;

              async function loadCombatStats() {
                const res = await fetch(\"/api/combat\");
                const data = await res.json();
                const body = document.getElementById(\"combat-body\");
                body.innerHTML = \"\";
                data.forEach(player => {
                  const row = document.createElement(\"tr\");
                  row.innerHTML = `<td>${player.player_name}</td><td>${player.total_kills}</td><td>${player.pvp_kills}</td><td>${player.pve_kills}</td><td>${player.pvp_ratio}%</td><td>${player.deaths}</td><td>${player.kd_ratio}</td><td>${player.kill_streak}</td><td>${player.max_kill_streak}</td>`;
                  body.appendChild(row);
                });

                // Load kill streaks table
                const streaksBody = document.getElementById(\"streaks-body\");
                streaksBody.innerHTML = \"\";
                // Sort by max kill streak and take top 10
                const topStreaks = data.sort((a, b) => b.max_kill_streak - a.max_kill_streak).slice(0, 10);
                topStreaks.forEach(player => {
                  const row = document.createElement(\"tr\");
                  row.innerHTML = `<td>${player.player_name}</td><td>${player.kill_streak}</td><td>${player.max_kill_streak}</td>`;
                  streaksBody.appendChild(row);
                });
              }

              async function loadWeaponChart() {
                const res = await fetch(\"/api/weapons\");
                const data = await res.json();
                if (data.length === 0) return;

                // Aggregate weapons across all players and take top 10 most used
                const weaponMap = {};
                data.forEach(w => {
                  if (!weaponMap[w.weapon]) {
                    weaponMap[w.weapon] = 0;
                  }
                  weaponMap[w.weapon] += w.kills;
                });

                const sortedWeapons = Object.entries(weaponMap)
                  .sort((a, b) => b[1] - a[1])
                  .slice(0, 10);

                const ctx = document.getElementById(\"weaponChart\").getContext(\"2d\");
                if (weaponChartInstance) {
                  weaponChartInstance.destroy();
                }
                weaponChartInstance = new Chart(ctx, {
                  type: \"bar\",
                  data: {
                    labels: sortedWeapons.map(w => w[0]),
                    datasets: [{
                      label: \"Kills\",
                      data: sortedWeapons.map(w => w[1]),
                      backgroundColor: \"#FF6384\",
                      borderColor: \"#FF6384\",
                      borderWidth: 1
                    }]
                  },
                  options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    indexAxis: \"y\",
                    plugins: {
                      legend: {
                        display: false
                      }
                    },
                    scales: {
                      x: {
                        beginAtZero: true,
                        ticks: {
                          stepSize: 1
                        }
                      }
                    }
                  }
                });
              }

              let activityTrendsChartInstance = null;
              let hourlyActivityChartInstance = null;

              async function loadSessionInsights() {
                const res = await fetch(\"/api/sessions/insights\");
                const data = await res.json();
                
                document.getElementById(\"insights-total\").textContent = data.total_sessions || 0;
                
                const avgMin = Math.floor((data.avg_duration_seconds || 0) / 60);
                const avgSec = (data.avg_duration_seconds || 0) % 60;
                document.getElementById(\"insights-avg\").textContent = `${avgMin}m ${avgSec}s`;
                
                const maxMin = Math.floor((data.max_duration_seconds || 0) / 60);
                const maxSec = (data.max_duration_seconds || 0) % 60;
                document.getElementById(\"insights-max\").textContent = `${maxMin}m ${maxSec}s`;
                
                const minMin = Math.floor((data.min_duration_seconds || 0) / 60);
                const minSec = (data.min_duration_seconds || 0) % 60;
                document.getElementById(\"insights-min\").textContent = `${minMin}m ${minSec}s`;
              }

              async function loadActivityTrends() {
                const res = await fetch(\"/api/activity/trends?limit=30\");
                const data = await res.json();
                
                if (data.length === 0) return;
                
                // Reverse to show oldest to newest
                const reversed = data.reverse();
                
                const ctx = document.getElementById(\"activityTrendsChart\").getContext(\"2d\");
                if (activityTrendsChartInstance) {
                  activityTrendsChartInstance.destroy();
                }
                activityTrendsChartInstance = new Chart(ctx, {
                  type: \"line\",
                  data: {
                    labels: reversed.map(d => d.date),
                    datasets: [
                      {
                        label: \"Unique Players\",
                        data: reversed.map(d => d.unique_players),
                        borderColor: \"#36A2EB\",
                        backgroundColor: \"rgba(54, 162, 235, 0.1)\",
                        borderWidth: 2,
                        tension: 0.3
                      },
                      {
                        label: \"Total Sessions\",
                        data: reversed.map(d => d.total_sessions),
                        borderColor: \"#FF6384\",
                        backgroundColor: \"rgba(255, 99, 132, 0.1)\",
                        borderWidth: 2,
                        tension: 0.3
                      }
                    ]
                  },
                  options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                      legend: {
                        position: \"top\"
                      }
                    },
                    scales: {
                      y: {
                        beginAtZero: true
                      }
                    }
                  }
                });
              }

              async function loadHourlyActivity() {
                const res = await fetch(\"/api/activity/hourly\");
                const data = await res.json();
                
                // Create full 24-hour array
                const hourlyData = Array(24).fill(0);
                data.forEach(item => {
                  hourlyData[item.hour] = item.joins;
                });
                
                const ctx = document.getElementById(\"hourlyActivityChart\").getContext(\"2d\");
                if (hourlyActivityChartInstance) {
                  hourlyActivityChartInstance.destroy();
                }
                hourlyActivityChartInstance = new Chart(ctx, {
                  type: \"bar\",
                  data: {
                    labels: Array.from({length: 24}, (_, i) => `${i}:00`),
                    datasets: [{
                      label: \"Joins\",
                      data: hourlyData,
                      backgroundColor: \"#FFCE56\",
                      borderColor: \"#FFCE56\",
                      borderWidth: 1
                    }]
                  },
                  options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                      legend: {
                        display: false
                      }
                    },
                    scales: {
                      y: {
                        beginAtZero: true,
                        ticks: {
                          stepSize: 1
                        }
                      }
                    }
                  }
                });
              }

              async function loadLeaderboards() {
                const types = [
                  { type: 'playtime', id: 'leaderboard-playtime', formatter: (val) => {
                    const hours = Math.floor(val / 3600);
                    const minutes = Math.floor((val % 3600) / 60);
                    return `${hours}h ${minutes}m`;
                  }},
                  { type: 'kd_ratio', id: 'leaderboard-kd', formatter: (val) => val.toFixed(2) },
                  { type: 'kill_streak', id: 'leaderboard-streak', formatter: (val) => val },
                  { type: 'total_kills', id: 'leaderboard-kills', formatter: (val) => val },
                  { type: 'pvp_kills', id: 'leaderboard-pvp', formatter: (val) => val },
                  { type: 'sessions', id: 'leaderboard-sessions', formatter: (val) => val }
                ];

                for (const leaderboard of types) {
                  const res = await fetch(`/api/leaderboard/${leaderboard.type}?limit=10`);
                  const data = await res.json();
                  const tbody = document.getElementById(leaderboard.id);
                  tbody.innerHTML = "";

                  data.forEach(entry => {
                    if (entry.error) return;
                    const row = document.createElement("tr");
                    const rankStyle = entry.rank === 1 ? 'color: #FFD700; font-weight: bold;' :
                                     entry.rank === 2 ? 'color: #C0C0C0; font-weight: bold;' :
                                     entry.rank === 3 ? 'color: #CD7F32; font-weight: bold;' : '';
                    const playerLink = `<a href="/player/${entry.player_uuid}" style="color: var(--accent); text-decoration: none;">${entry.player_name}</a>`;
                    row.innerHTML = `<td style="${rankStyle}">${entry.rank}</td><td>${playerLink}</td><td>${leaderboard.formatter(entry.value)}</td>`;
                    tbody.appendChild(row);
                  });
                }
              }

              async function loadServerOverview() {
                // Fetch data from multiple endpoints
                const [summaryRes, metricsRes, onlineRes] = await Promise.all([
                  fetch("/api/summary"),
                  fetch("/api/metrics"),
                  fetch("/api/players/online")
                ]);
                
                const summary = await summaryRes.json();
                const metrics = await metricsRes.json();
                const online = await onlineRes.json();
                
                // TPS with status
                const tps = metrics.avgTps || 20;
                document.getElementById("overview-tps").textContent = tps.toFixed(1);
                let tpsStatus = "Excellent";
                if (tps < 10) tpsStatus = "Critical";
                else if (tps < 15) tpsStatus = "Poor";
                else if (tps < 18) tpsStatus = "Fair";
                else if (tps < 19.5) tpsStatus = "Good";
                document.getElementById("overview-tps-status").textContent = `TPS (${tpsStatus})`;
                
                // RAM with percentage
                const ramMB = Math.round(metrics.avgRamUsedMb || 0);
                document.getElementById("overview-ram").textContent = `${ramMB} MB`;
                const ramMaxMB = ramMB > 0 ? Math.round(ramMB / 0.7) : 0; // Estimate max
                const ramPercent = ramMaxMB > 0 ? Math.round((ramMB / ramMaxMB) * 100) : 0;
                document.getElementById("overview-ram-percent").textContent = `${ramPercent}% utilized`;
                
                // Online players
                document.getElementById("overview-online").textContent = online.length;
                document.getElementById("overview-total-players").textContent = `${summary.uniquePlayers} total players`;
                
                // Recent activity (joins + leaves today)
                const activityCount = summary.joins + summary.leaves;
                document.getElementById("overview-activity").textContent = activityCount;
                
                // Total playtime
                const playtimeHours = Math.floor(summary.totalPlaytimeSeconds / 3600);
                document.getElementById("overview-playtime").textContent = `${playtimeHours}h`;
                document.getElementById("overview-sessions").textContent = `${summary.totalSessions} sessions`;
                
                // Combat stats (fetch from combat endpoint)
                try {
                  const combatRes = await fetch("/api/combat");
                  const combat = await combatRes.json();
                  let totalKills = 0;
                  let totalDeaths = 0;
                  combat.forEach(player => {
                    totalKills += player.total_kills || 0;
                    totalDeaths += player.total_deaths || 0;
                  });
                  document.getElementById("overview-kills").textContent = totalKills;
                  document.getElementById("overview-deaths").textContent = `${totalDeaths} deaths`;
                } catch (e) {
                  document.getElementById("overview-kills").textContent = "0";
                  document.getElementById("overview-deaths").textContent = "0 deaths";
                }
              }

              async function loadOnlinePlayers() {
                const res = await fetch("/api/players/online");
                const data = await res.json();
                const container = document.getElementById("online-players-list");
                const countSpan = document.getElementById("online-count");
                
                countSpan.textContent = data.length;
                
                if (data.length === 0) {
                  container.innerHTML = '<p style="color: var(--muted);">No players online</p>';
                  return;
                }
                
                container.innerHTML = "";
                data.forEach(player => {
                  const durationMinutes = Math.floor(player.session_duration_seconds / 60);
                  const durationSeconds = player.session_duration_seconds % 60;
                  const durationDisplay = durationMinutes > 0 
                    ? `${durationMinutes}m ${durationSeconds}s`
                    : `${durationSeconds}s`;
                  
                  const playerDiv = document.createElement("div");
                  playerDiv.style.cssText = "padding: 8px 0; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; align-items: center;";
                  playerDiv.innerHTML = `
                    <div>
                      <a href="/player/${player.player_uuid}" style="color: var(--accent); text-decoration: none; font-weight: 600;">
                        ${player.player_name}
                      </a>
                    </div>
                    <div style="color: var(--muted); font-size: 12px;">
                      ${durationDisplay}
                    </div>
                  `;
                  container.appendChild(playerDiv);
                });
                
                // Remove border from last item
                const lastChild = container.lastElementChild;
                if (lastChild) {
                  lastChild.style.borderBottom = "none";
                }
              }

              async function refreshAll() {
                await Promise.all([loadServerOverview(), loadSummary(), loadEvents(), loadPlayers(), loadSessions(), loadKills(), loadPlaytimeDetails(), loadPlaytimeChart(), loadKillsChart(), loadServerMetrics(), loadCombatStats(), loadWeaponChart(), loadSessionInsights(), loadActivityTrends(), loadHourlyActivity(), loadLeaderboards(), loadOnlinePlayers()]);
              }

              // Initialize tables
              async function initTables() {
                await refreshAll();
                
                // Make tables sortable
                makeTableSortable('players-table');
                makeTableSortable('sessions-table');
                makeTableSortable('playtime-table');
                makeTableSortable('combat-table');
                makeTableSortable('kills-table');
                
                // Add search functionality
                addTableSearch('players-table', 'search-players');
                addTableSearch('sessions-table', 'search-sessions');
                addTableSearch('playtime-table', 'search-playtime');
                addTableSearch('combat-table', 'search-combat');
                addTableSearch('kills-table', 'search-kills');
                
                // Initial count updates
                ['players', 'sessions', 'playtime', 'combat', 'kills'].forEach(name => {
                  const table = document.getElementById(`${name}-table`);
                  const badge = document.getElementById(`search-${name}-count`);
                  if (table && badge) {
                    const rows = table.querySelectorAll('tbody tr');
                    badge.textContent = `${rows.length} results`;
                  }
                });
              }

              initTables();
              setInterval(refreshAll, 5000);
            </script>
          </body>
        </html>
        """;

    private static final class PlayerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 4) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            String playerUuid = parts[3];
            String query = exchange.getRequestURI().getRawQuery();
            
            String json;
            if (query != null && query.contains("sessions")) {
                int limit = 50;
                try {
                    Map<String, String> params = parseQuery(exchange.getRequestURI());
                    String limitValue = params.get("limit");
                    if (limitValue != null) {
                        limit = Integer.parseInt(limitValue);
                        limit = Math.max(1, Math.min(limit, 500));
                    }
                } catch (NumberFormatException ex) {
                    // Use default
                }
                json = PlayerAnalyticsDb.getPlayerSessionsJson(playerUuid, limit);
            } else {
                json = PlayerAnalyticsDb.getPlayerProfileJson(playerUuid);
            }
            
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private static final class PlayerPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length < 3) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            String playerUuid = parts[2];
            
            String html = PLAYER_PROFILE_HTML.replace("{PLAYER_UUID}", playerUuid);
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private static final String PLAYER_PROFILE_HTML = """
        <!doctype html>
        <html lang=\"en\">
          <head>
            <meta charset=\"utf-8\" />
            <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
            <title>Player Profile - Playeranalytics</title>
            <script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>
            <style>
              * { margin: 0; padding: 0; box-sizing: border-box; }
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
                font-family: "Space Grotesk", "IBM Plex Sans", "Segoe UI", sans-serif;
                background: var(--bg);
                color: var(--ink);
              }
              header {
                padding: 24px;
                background: var(--card);
                border-bottom: 1px solid var(--line);
                display: flex;
                justify-content: space-between;
                align-items: center;
              }
              h1 { font-size: 28px; }
              h2 { font-size: 18px; margin-top: 16px; margin-bottom: 8px; }
              a { color: var(--accent); text-decoration: none; }
              a:hover { text-decoration: underline; }
              main { padding: 24px; max-width: 1200px; margin: 0 auto; }
              .stats-grid {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                gap: 12px;
                margin-bottom: 24px;
              }
              .stat-card {
                background: var(--card);
                border: 1px solid var(--line);
                border-radius: 12px;
                padding: 16px;
              }
              .stat-label { color: var(--muted); font-size: 12px; }
              .stat-value { font-size: 24px; font-weight: bold; margin-top: 4px; }
              table {
                width: 100%;
                background: var(--card);
                border: 1px solid var(--line);
                border-radius: 12px;
                border-collapse: collapse;
                margin: 20px 0;
              }
              th { background: #f0ebe7; font-weight: 600; padding: 12px; text-align: left; }
              td { padding: 12px; border-top: 1px solid var(--line); }
              tr:hover { background: #faf9f7; }
            </style>
          </head>
          <body>
            <header>
              <h1>Player Profile</h1>
              <a href=\"/\">← Back to Dashboard</a>
            </header>
            <main>
              <h2 id=\"playerName\">Loading...</h2>
              <div class=\"stats-grid\">
                <div class=\"stat-card\">
                  <div class=\"stat-label\">Total Playtime</div>
                  <div class=\"stat-value\" id=\"playtime\">-</div>
                </div>
                <div class=\"stat-card\">
                  <div class=\"stat-label\">Active Playtime</div>
                  <div class=\"stat-value\" id=\"activetime\">-</div>
                </div>
                <div class=\"stat-card\">
                  <div class=\"stat-label\">Kills / Deaths</div>
                  <div class=\"stat-value\" id=\"kills\">/</div>
                </div>
                <div class=\"stat-card\">
                  <div class=\"stat-label\">K/D Ratio</div>
                  <div class=\"stat-value\" id=\"kdratio\">-</div>
                </div>
              </div>
              
              <h2>Session History</h2>
              <table>
                <thead>
                  <tr>
                    <th>Session Start (UTC)</th>
                    <th>Session End (UTC)</th>
                    <th>Duration</th>
                  </tr>
                </thead>
                <tbody id=\"sessions-table\"></tbody>
              </table>
            </main>
            <script>
              const playerUuid = \"{PLAYER_UUID}\";
              
              async function loadPlayerProfile() {
                const res = await fetch(`/api/player/${playerUuid}`);
                const data = await res.json();
                
                if (data.error) {
                  document.getElementById(\"playerName\").textContent = \"Player not found\";
                  return;
                }
                
                document.getElementById(\"playerName\").textContent = data.playerName;
                
                const playtimeHours = Math.floor(data.totalPlaytimeSeconds / 3600);
                const playtimeMinutes = Math.floor((data.totalPlaytimeSeconds % 3600) / 60);
                document.getElementById(\"playtime\").textContent = `${playtimeHours}h ${playtimeMinutes}m`;
                
                const activeHours = Math.floor(data.activePlaytimeSeconds / 3600);
                const activeMinutes = Math.floor((data.activePlaytimeSeconds % 3600) / 60);
                document.getElementById(\"activetime\").textContent = `${activeHours}h ${activeMinutes}m`;
                
                document.getElementById(\"kills\").textContent = `${data.kills} / ${data.deaths}`;
                document.getElementById(\"kdratio\").textContent = data.kdRatio;
              }
              
              async function loadPlayerSessions() {
                const res = await fetch(`/api/player/${playerUuid}?sessions=true&limit=50`);
                const data = await res.json();
                const tbody = document.getElementById(\"sessions-table\");
                tbody.innerHTML = \"\";
                
                data.forEach(session => {
                  const row = document.createElement(\"tr\");
                  const duration = `${Math.floor(session.durationSeconds / 60)}m ${session.durationSeconds % 60}s`;
                  row.innerHTML = `<td>${session.sessionStart}</td><td>${session.sessionEnd}</td><td>${duration}</td>`;
                  tbody.appendChild(row);
                });
              }
              
              loadPlayerProfile();
              loadPlayerSessions();
            </script>
          </body>
        </html>
        """;

    static class DeathCausesHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath();
            String playerUuid = path.replace("/api/combat/deaths/", "");
            
            if (playerUuid.isEmpty()) {
                handleJson(exchange, "[]");
                return;
            }
            
            String json = PlayerAnalyticsDb.getDeathCausesJson(playerUuid);
            handleJson(exchange, json);
        }
    }

    static class KillMatrixHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath();
            String killerUuid = path.replace("/api/combat/matrix/", "");
            
            if (killerUuid.isEmpty()) {
                handleJson(exchange, "[]");
                return;
            }
            
            String json = PlayerAnalyticsDb.getKillMatrixJson(killerUuid);
            handleJson(exchange, json);
        }
    }

    static class WorldHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath();
            String worldName = path.replace("/api/world/", "");
            
            if (worldName.isEmpty()) {
                handleJson(exchange, "{}");
                return;
            }
            
            try {
                worldName = java.net.URLDecoder.decode(worldName, "UTF-8");
            } catch (Exception e) {
                handleJson(exchange, "{\"error\":\"Invalid world name\"}");
                return;
            }
            
            String json = PlayerAnalyticsDb.getWorldStatsJson(worldName);
            handleJson(exchange, json);
        }
    }

    static class LeaderboardHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath();
            String type = path.replace("/api/leaderboard/", "");
            
            if (type.isEmpty()) {
                handleJson(exchange, "{\"error\":\"Leaderboard type required\"}");
                return;
            }
            
            // Parse limit from query parameters
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            int limit = 10; // Default limit
            try {
                String limitStr = params.get("limit");
                if (limitStr != null) {
                    limit = Math.max(1, Math.min(Integer.parseInt(limitStr), 50));
                }
            } catch (NumberFormatException e) {
                // Use default
            }
            
            String json = PlayerAnalyticsDb.getLeaderboardJson(type, limit);
            handleJson(exchange, json);
        }
    }
}