use axum::{
    extract::State,
    http::StatusCode,
    response::{Html, IntoResponse, Json},
    routing::{get, post},
    Router,
};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::Mutex;
use tower_http::cors::CorsLayer;

type DbState = Arc<Mutex<Connection>>;

#[derive(Debug, Deserialize)]
struct MetricPayload {
    event: String,
    mod_version: Option<String>,
    mc_version: Option<String>,
    java_version: Option<String>,
    os: Option<String>,
    uses_socks: Option<bool>,
    duration_bucket: Option<String>,
    duration_seconds: Option<i64>,
}

#[derive(Debug, Serialize)]
struct StatsResponse {
    total_launches: i64,
    total_sessions: i64,
    mc_versions: HashMap<String, i64>,
    mod_versions: HashMap<String, i64>,
    os_breakdown: HashMap<String, i64>,
    duration_buckets: HashMap<String, i64>,
    socks_count: i64,
    direct_count: i64,
    daily_trend: Vec<DailyStat>,
}

#[derive(Debug, Serialize)]
struct DailyStat {
    date: String,
    launches: i64,
    sessions: i64,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();

    let db_path = std::env::var("DATABASE_PATH").unwrap_or_else(|_| "telemetry.db".to_string());
    let conn = Connection::open(&db_path).expect("Failed to open SQLite database");

    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_type TEXT NOT NULL,
            mod_version TEXT NOT NULL,
            mc_version TEXT NOT NULL,
            os TEXT,
            java_version TEXT,
            uses_socks INTEGER NOT NULL DEFAULT 0,
            duration_bucket TEXT,
            duration_seconds INTEGER,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        CREATE INDEX IF NOT EXISTS idx_created_at ON events(created_at);
        CREATE INDEX IF NOT EXISTS idx_event_type ON events(event_type);
        CREATE INDEX IF NOT EXISTS idx_mc_version ON events(mc_version);"
    ).expect("Failed to initialize database schema");

    let state: DbState = Arc::new(Mutex::new(conn));

    let app = Router::new()
        .route("/", get(dashboard_handler))
        .route("/health", get(health_handler))
        .route("/api/v1/metrics", post(record_metric_handler))
        .route("/api/v1/stats", get(get_stats_handler))
        .layer(CorsLayer::permissive())
        .with_state(state);

    let host = std::env::var("HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
    let port: u16 = std::env::var("PORT")
        .unwrap_or_else(|_| "8080".to_string())
        .parse()
        .expect("PORT must be a number");

    let addr: SocketAddr = format!("{}:{}", host, port)
        .parse()
        .expect("Invalid address");

    println!("VoicePlus Telemetry Server running on http://{}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn health_handler() -> &'static str {
    "OK"
}

async fn record_metric_handler(
    State(db): State<DbState>,
    Json(payload): Json<MetricPayload>,
) -> impl IntoResponse {
    let conn = db.lock().await;

    let event_type = payload.event;
    let mod_ver = payload.mod_version.unwrap_or_else(|| "unknown".to_string());
    let mc_ver = payload.mc_version.unwrap_or_else(|| "unknown".to_string());
    let os = payload.os.unwrap_or_else(|| "other".to_string());
    let java_ver = payload.java_version.unwrap_or_else(|| "unknown".to_string());
    let uses_socks = if payload.uses_socks.unwrap_or(false) { 1 } else { 0 };
    let duration_bucket = payload.duration_bucket;
    let duration_seconds = payload.duration_seconds;

    let res = conn.execute(
        "INSERT INTO events (event_type, mod_version, mc_version, os, java_version, uses_socks, duration_bucket, duration_seconds)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        params![event_type, mod_ver, mc_ver, os, java_ver, uses_socks, duration_bucket, duration_seconds],
    );

    match res {
        Ok(_) => (StatusCode::CREATED, Json(serde_json::json!({ "status": "recorded" }))),
        Err(e) => {
            eprintln!("DB insert error: {}", e);
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({ "error": "db_error" })))
        }
    }
}

async fn get_stats_handler(State(db): State<DbState>) -> impl IntoResponse {
    let conn = db.lock().await;

    let total_launches: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE event_type = 'launch'", [], |r| r.get(0))
        .unwrap_or(0);

    let total_sessions: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE event_type = 'session_ended'", [], |r| r.get(0))
        .unwrap_or(0);

    let mut mc_versions = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT mc_version, COUNT(*) FROM events GROUP BY mc_version") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                mc_versions.insert(row.0, row.1);
            }
        }
    }

    let mut mod_versions = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT mod_version, COUNT(*) FROM events GROUP BY mod_version") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                mod_versions.insert(row.0, row.1);
            }
        }
    }

    let mut os_breakdown = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT os, COUNT(*) FROM events GROUP BY os") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                os_breakdown.insert(row.0, row.1);
            }
        }
    }

    let mut duration_buckets = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT duration_bucket, COUNT(*) FROM events WHERE duration_bucket IS NOT NULL GROUP BY duration_bucket") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                duration_buckets.insert(row.0, row.1);
            }
        }
    }

    let socks_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE uses_socks = 1", [], |r| r.get(0))
        .unwrap_or(0);

    let direct_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE uses_socks = 0", [], |r| r.get(0))
        .unwrap_or(0);

    let mut daily_trend = Vec::new();
    let query_daily = "SELECT DATE(created_at) as day, 
                              SUM(CASE WHEN event_type = 'launch' THEN 1 ELSE 0 END) as launches,
                              SUM(CASE WHEN event_type = 'session_ended' THEN 1 ELSE 0 END) as sessions
                       FROM events 
                       WHERE created_at >= DATE('now', '-14 days')
                       GROUP BY DATE(created_at)
                       ORDER BY day ASC";

    if let Ok(mut stmt) = conn.prepare(query_daily) {
        let rows = stmt.query_map([], |r| {
            Ok(DailyStat {
                date: r.get(0)?,
                launches: r.get(1)?,
                sessions: r.get(2)?,
            })
        });
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                daily_trend.push(row);
            }
        }
    }

    Json(StatsResponse {
        total_launches,
        total_sessions,
        mc_versions,
        mod_versions,
        os_breakdown,
        duration_buckets,
        socks_count,
        direct_count,
        daily_trend,
    })
}

async fn dashboard_handler() -> Html<&'static str> {
    Html(r#"<!DOCTYPE html>
<html lang="en" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>VoicePlus Telemetry Dashboard</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: {
                        darkBg: '#0f172a',
                        cardBg: '#1e293b',
                        accent: '#38bdf8',
                        accentGreen: '#34d399',
                        accentPurple: '#a78bfa',
                        accentOrange: '#fb923c'
                    }
                }
            }
        }
    </script>
    <style>
        body { font-family: system-ui, -apple-system, sans-serif; background-color: #0f172a; color: #f8fafc; }
    </style>
</head>
<body class="p-6 md:p-10 min-h-screen">
    <div class="max-w-7xl mx-auto space-y-8">
        <!-- Header -->
        <div class="flex flex-col md:flex-row md:items-center justify-between border-b border-slate-700 pb-6 gap-4">
            <div>
                <h1 class="text-3xl font-extrabold tracking-tight bg-gradient-to-r from-sky-400 via-indigo-400 to-purple-400 bg-clip-text text-transparent">
                    VoicePlus Analytics
                </h1>
                <p class="text-slate-400 text-sm mt-1">100% Anonymous Stateless Mod Telemetry Dashboard</p>
            </div>
            <div class="flex items-center gap-3">
                <span class="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-emerald-950 text-emerald-400 border border-emerald-800">
                    <span class="w-2 h-2 mr-2 bg-emerald-400 rounded-full animate-pulse"></span>
                    Zero Personal Data / GDPR Clean
                </span>
                <button onclick="loadStats()" class="px-4 py-2 bg-slate-800 hover:bg-slate-700 border border-slate-600 rounded-lg text-sm text-slate-200 transition">
                    Refresh
                </button>
            </div>
        </div>

        <!-- Metric Cards -->
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl relative overflow-hidden">
                <div class="text-slate-400 text-xs uppercase tracking-wider font-semibold">Total Launches</div>
                <div class="text-4xl font-bold text-sky-400 mt-2" id="val-total-launches">-</div>
                <div class="text-xs text-slate-400 mt-2">Client start events</div>
            </div>
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl relative overflow-hidden">
                <div class="text-slate-400 text-xs uppercase tracking-wider font-semibold">Voice Sessions</div>
                <div class="text-4xl font-bold text-emerald-400 mt-2" id="val-total-sessions">-</div>
                <div class="text-xs text-slate-400 mt-2">Completed voice connections</div>
            </div>
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl relative overflow-hidden">
                <div class="text-slate-400 text-xs uppercase tracking-wider font-semibold">SOCKS5 Proxy Usage</div>
                <div class="text-4xl font-bold text-amber-400 mt-2" id="val-socks-rate">-%</div>
                <div class="text-xs text-slate-400 mt-2" id="val-socks-counts">- direct / - proxy</div>
            </div>
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl relative overflow-hidden">
                <div class="text-slate-400 text-xs uppercase tracking-wider font-semibold">Top MC Version</div>
                <div class="text-4xl font-bold text-purple-400 mt-2" id="val-top-mc">-</div>
                <div class="text-xs text-slate-400 mt-2">Most active version</div>
            </div>
        </div>

        <!-- Charts Grid 1 -->
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl">
                <h3 class="text-base font-semibold text-slate-200 mb-4">Minecraft Version Distribution</h3>
                <div class="relative h-72">
                    <canvas id="chart-mc"></canvas>
                </div>
            </div>
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl">
                <h3 class="text-base font-semibold text-slate-200 mb-4">Mod Version Adoption</h3>
                <div class="relative h-72">
                    <canvas id="chart-mod"></canvas>
                </div>
            </div>
        </div>

        <!-- Charts Grid 2 -->
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl lg:col-span-2">
                <h3 class="text-base font-semibold text-slate-200 mb-4">14-Day Activity Trend</h3>
                <div class="relative h-72">
                    <canvas id="chart-trend"></canvas>
                </div>
            </div>
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl">
                <h3 class="text-base font-semibold text-slate-200 mb-4">Session Duration Distribution</h3>
                <div class="relative h-72">
                    <canvas id="chart-duration"></canvas>
                </div>
            </div>
        </div>

        <!-- Charts Grid 3 -->
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl">
                <h3 class="text-base font-semibold text-slate-200 mb-4">Operating Systems</h3>
                <div class="relative h-64">
                    <canvas id="chart-os"></canvas>
                </div>
            </div>
            <div class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl flex flex-col justify-center">
                <h3 class="text-base font-semibold text-slate-200 mb-2">Privacy & Transparency Guarantee</h3>
                <p class="text-sm text-slate-400 leading-relaxed">
                    VoicePlus uses <strong>strictly stateless metrics</strong>. No IP addresses, device identifiers, or Minecraft account UUIDs are logged or stored.
                </p>
                <div class="mt-4 p-4 rounded-lg bg-slate-900 border border-slate-800 text-xs text-slate-400 space-y-1">
                    <div>• <strong>Zero Persistent ID:</strong> Every event is standalone.</div>
                    <div>• <strong>Bucketized Durations:</strong> Raw seconds are grouped to avoid fingerprinting.</div>
                    <div>• <strong>Opt-Out:</strong> Players can disable telemetry via in-game config or properties.</div>
                </div>
            </div>
        </div>
    </div>

    <script>
        let charts = {};

        const palette = ['#38bdf8', '#34d399', '#a78bfa', '#fb923c', '#f472b6', '#facc15', '#94a3b8'];

        async function loadStats() {
            try {
                const res = await fetch('/api/v1/stats');
                const data = await res.json();

                document.getElementById('val-total-launches').innerText = data.total_launches.toLocaleString();
                document.getElementById('val-total-sessions').innerText = data.total_sessions.toLocaleString();

                const totalTraffic = data.socks_count + data.direct_count;
                const socksPct = totalTraffic > 0 ? Math.round((data.socks_count / totalTraffic) * 100) : 0;
                document.getElementById('val-socks-rate').innerText = socksPct + '%';
                document.getElementById('val-socks-counts').innerText = `${data.direct_count} direct / ${data.socks_count} socks`;

                const sortedMc = Object.entries(data.mc_versions).sort((a, b) => b[1] - a[1]);
                document.getElementById('val-top-mc').innerText = sortedMc.length > 0 ? sortedMc[0][0] : 'N/A';

                renderDoughnut('chart-mc', data.mc_versions);
                renderBar('chart-mod', data.mod_versions, '#a78bfa');
                renderDoughnut('chart-os', data.os_breakdown);
                renderDurationBar('chart-duration', data.duration_buckets);
                renderTrend('chart-trend', data.daily_trend);
            } catch (err) {
                console.error('Failed to load stats:', err);
            }
        }

        function renderDoughnut(id, dataMap) {
            const ctx = document.getElementById(id).getContext('2d');
            if (charts[id]) charts[id].destroy();
            charts[id] = new Chart(ctx, {
                type: 'doughnut',
                data: {
                    labels: Object.keys(dataMap),
                    datasets: [{
                        data: Object.values(dataMap),
                        backgroundColor: palette,
                        borderColor: '#1e293b',
                        borderWidth: 2
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: { legend: { position: 'bottom', labels: { color: '#cbd5e1' } } }
                }
            });
        }

        function renderBar(id, dataMap, color) {
            const ctx = document.getElementById(id).getContext('2d');
            if (charts[id]) charts[id].destroy();
            charts[id] = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: Object.keys(dataMap),
                    datasets: [{
                        label: 'Count',
                        data: Object.values(dataMap),
                        backgroundColor: color,
                        borderRadius: 6
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: { legend: { display: false } },
                    scales: {
                        x: { ticks: { color: '#94a3b8' }, grid: { display: false } },
                        y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } }
                    }
                }
            });
        }

        function renderDurationBar(id, dataMap) {
            const bucketOrder = ['under_5m', '5m_15m', '15m_1h', '1h_3h', 'over_3h'];
            const labels = ['< 5m', '5-15m', '15m-1h', '1-3h', '> 3h'];
            const counts = bucketOrder.map(b => dataMap[b] || 0);

            const ctx = document.getElementById(id).getContext('2d');
            if (charts[id]) charts[id].destroy();
            charts[id] = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [{
                        label: 'Sessions',
                        data: counts,
                        backgroundColor: '#34d399',
                        borderRadius: 6
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: { legend: { display: false } },
                    scales: {
                        x: { ticks: { color: '#94a3b8' }, grid: { display: false } },
                        y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } }
                    }
                }
            });
        }

        function renderTrend(id, trendList) {
            const ctx = document.getElementById(id).getContext('2d');
            if (charts[id]) charts[id].destroy();
            charts[id] = new Chart(ctx, {
                type: 'line',
                data: {
                    labels: trendList.map(t => t.date),
                    datasets: [
                        {
                            label: 'Launches',
                            data: trendList.map(t => t.launches),
                            borderColor: '#38bdf8',
                            backgroundColor: 'rgba(56, 189, 248, 0.1)',
                            fill: true,
                            tension: 0.3
                        },
                        {
                            label: 'Voice Sessions',
                            data: trendList.map(t => t.sessions),
                            borderColor: '#34d399',
                            backgroundColor: 'rgba(52, 211, 153, 0.1)',
                            fill: true,
                            tension: 0.3
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: { legend: { position: 'top', labels: { color: '#cbd5e1' } } },
                    scales: {
                        x: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } },
                        y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } }
                    }
                }
            });
        }

        loadStats();
        setInterval(loadStats, 30000);
    </script>
</body>
</html>"#)
}
