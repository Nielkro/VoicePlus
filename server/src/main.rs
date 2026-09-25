use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Json, Response},
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

struct AppState {
    db: Mutex<Connection>,
    auth_token: Option<String>,
}

type SharedState = Arc<AppState>;

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

#[derive(Debug, Deserialize)]
struct AuthQuery {
    token: Option<String>,
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

    let auth_token = std::env::var("AUTH_TOKEN").ok().filter(|s| !s.trim().is_empty());
    if auth_token.is_some() {
        println!("Dashboard and Stats API are protected by AUTH_TOKEN");
    } else {
        println!("WARNING: AUTH_TOKEN is not set. Dashboard is currently public. Set AUTH_TOKEN=your_secret to protect it.");
    }

    let state: SharedState = Arc::new(AppState {
        db: Mutex::new(conn),
        auth_token,
    });

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

fn is_authorized(headers: &HeaderMap, query: &AuthQuery, required_token: &Option<String>) -> bool {
    let expected = match required_token {
        Some(token) => token,
        None => return true, // No auth configured
    };

    if let Some(ref q_token) = query.token {
        if q_token == expected {
            return true;
        }
    }

    if let Some(auth_header) = headers.get("Authorization") {
        if let Ok(auth_str) = auth_header.to_str() {
            if auth_str.starts_with("Bearer ") {
                let token = auth_str.trim_start_matches("Bearer ").trim();
                if token == expected {
                    return true;
                }
            }
        }
    }

    if let Some(token_header) = headers.get("X-Auth-Token") {
        if let Ok(token_str) = token_header.to_str() {
            if token_str == expected {
                return true;
            }
        }
    }

    false
}

async fn record_metric_handler(
    State(state): State<SharedState>,
    Json(payload): Json<MetricPayload>,
) -> impl IntoResponse {
    if payload.event != "launch" && payload.event != "session_ended" {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "invalid_event" })));
    }

    let conn = state.db.lock().await;

    let event_type = payload.event;
    let mod_ver = payload.mod_version.unwrap_or_else(|| "unknown".to_string());
    let mc_ver = payload.mc_version.unwrap_or_else(|| "unknown".to_string());
    let os = payload.os.unwrap_or_else(|| "other".to_string());
    let java_ver = payload.java_version.unwrap_or_else(|| "unknown".to_string());
    let uses_socks = if payload.uses_socks.unwrap_or(false) { 1 } else { 0 };
    let duration_bucket = payload.duration_bucket;
    let duration_seconds = payload.duration_seconds;

    // Safety checks against spam
    if mod_ver.len() > 32 || mc_ver.len() > 32 || os.len() > 32 || java_ver.len() > 32 {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "payload_too_large" })));
    }

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

async fn get_stats_handler(
    State(state): State<SharedState>,
    headers: HeaderMap,
    Query(query): Query<AuthQuery>,
) -> Response {
    if !is_authorized(&headers, &query, &state.auth_token) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "Unauthorized: invalid or missing token" })),
        ).into_response();
    }

    let conn = state.db.lock().await;

    let total_launches: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE event_type = 'launch'", [], |r| r.get(0))
        .unwrap_or(0);

    let total_sessions: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE event_type = 'session_ended'", [], |r| r.get(0))
        .unwrap_or(0);

    let mut mc_versions = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT mc_version, COUNT(*) FROM events WHERE event_type = 'launch' GROUP BY mc_version") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                mc_versions.insert(row.0, row.1);
            }
        }
    }

    let mut mod_versions: HashMap<String, i64> = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT mod_version, COUNT(*) FROM events WHERE event_type = 'launch' GROUP BY mod_version") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                let base_ver = row.0.split('-').next().unwrap_or(&row.0).to_string();
                *mod_versions.entry(base_ver).or_insert(0) += row.1;
            }
        }
    }

    let mut os_breakdown = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT os, COUNT(*) FROM events WHERE event_type = 'launch' GROUP BY os") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                os_breakdown.insert(row.0, row.1);
            }
        }
    }

    let mut duration_buckets = HashMap::new();
    if let Ok(mut stmt) = conn.prepare("SELECT duration_bucket, COUNT(*) FROM events WHERE event_type = 'session_ended' AND duration_bucket IS NOT NULL GROUP BY duration_bucket") {
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)));
        if let Ok(rows) = rows {
            for row in rows.flatten() {
                duration_buckets.insert(row.0, row.1);
            }
        }
    }

    let socks_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE event_type = 'launch' AND uses_socks = 1", [], |r| r.get(0))
        .unwrap_or(0);

    let direct_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM events WHERE event_type = 'launch' AND uses_socks = 0", [], |r| r.get(0))
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
    }).into_response()
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
    <!-- Auth Modal if unauthorized -->
    <div id="auth-modal" class="fixed inset-0 bg-slate-950/80 backdrop-blur-sm z-50 flex items-center justify-center p-4 hidden">
        <div class="bg-cardBg border border-slate-700 rounded-xl p-6 max-w-md w-full shadow-2xl space-y-4">
            <h2 class="text-xl font-bold text-slate-100">🔒 Access Restricted</h2>
            <p class="text-sm text-slate-400">This dashboard requires an administrator access token.</p>
            <div>
                <input type="password" id="token-input" placeholder="Enter AUTH_TOKEN" class="w-full bg-slate-900 border border-slate-700 rounded-lg px-4 py-2 text-sm text-slate-200 focus:outline-none focus:border-sky-500">
            </div>
            <div class="flex justify-end gap-3">
                <button onclick="saveToken()" class="px-4 py-2 bg-sky-600 hover:bg-sky-500 rounded-lg text-sm text-white font-medium transition">
                    Unlock Dashboard
                </button>
            </div>
        </div>
    </div>

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
                <span id="refresh-badge" class="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-slate-800 text-slate-300 border border-slate-700">
                    <span class="w-2 h-2 mr-2 bg-emerald-400 rounded-full animate-pulse"></span>
                    <span id="timer-text">Auto-refresh in 30s</span>
                </span>
                <button onclick="logoutToken()" title="Change access token" class="px-3 py-2 bg-slate-800 hover:bg-slate-700 border border-slate-600 rounded-lg text-xs text-slate-300 transition">
                    🔑 Key
                </button>
                <button onclick="manualRefresh()" class="px-4 py-2 bg-sky-600 hover:bg-sky-500 rounded-lg text-sm text-white font-medium transition shadow-lg shadow-sky-600/20">
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
            <div id="card-top-mc" class="bg-cardBg border border-slate-700/60 rounded-xl p-6 shadow-xl relative overflow-hidden transition-all duration-500">
                <div class="text-slate-400 text-xs uppercase tracking-wider font-semibold">Top MC Version</div>
                <div class="text-4xl font-bold mt-2 text-slate-100" id="val-top-mc">-</div>
                <div class="text-xs text-slate-400 mt-2" id="val-top-mc-pct">Most active version</div>
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
                    <div>• <strong>Deterministic Colors:</strong> Stable analytics view across auto-refreshes.</div>
                </div>
            </div>
        </div>
    </div>

    <script>
        let charts = {};
        let countdown = 30;

        const fixedColors = {
            '26.3': '#38bdf8',
            '26.2': '#34d399',
            '26.1': '#a78bfa',
            '1.21.11': '#fb923c',
            '1.21.4': '#f472b6',
            '1.21.3': '#facc15',
            '1.21.2': '#94a3b8',
            'linux': '#34d399',
            'windows': '#38bdf8',
            'macos': '#a78bfa',
            'other': '#64748b'
        };

        const fallbackPalette = [
            '#38bdf8', '#34d399', '#a78bfa', '#fb923c', '#f472b6', 
            '#facc15', '#2dd4bf', '#818cf8', '#f87171', '#94a3b8'
        ];

        function getDeterministicColor(label) {
            const key = String(label).toLowerCase().trim();
            if (fixedColors[key]) return fixedColors[key];
            
            // Stable hash
            let hash = 0;
            for (let i = 0; i < key.length; i++) {
                hash = (hash << 5) - hash + key.charCodeAt(i);
                hash |= 0;
            }
            const index = Math.abs(hash) % fallbackPalette.length;
            return fallbackPalette[index];
        }

        function sortMapEntries(dataMap) {
            return Object.entries(dataMap).sort((a, b) => {
                if (b[1] !== a[1]) return b[1] - a[1];
                return String(a[0]).localeCompare(String(b[0]));
            });
        }

        function getToken() {
            const urlParams = new URLSearchParams(window.location.search);
            return urlParams.get('token') || localStorage.getItem('vp_auth_token') || '';
        }

        function saveToken() {
            const val = document.getElementById('token-input').value.trim();
            if (val) {
                localStorage.setItem('vp_auth_token', val);
                document.getElementById('auth-modal').classList.add('hidden');
                loadStats();
            }
        }

        function logoutToken() {
            localStorage.removeItem('vp_auth_token');
            document.getElementById('token-input').value = '';
            document.getElementById('auth-modal').classList.remove('hidden');
        }

        function manualRefresh() {
            countdown = 30;
            loadStats();
        }

        async function loadStats() {
            const token = getToken();
            const headers = {};
            if (token) {
                headers['Authorization'] = 'Bearer ' + token;
            }

            try {
                const res = await fetch('/api/v1/stats', { headers });
                if (res.status === 401) {
                    document.getElementById('auth-modal').classList.remove('hidden');
                    return;
                }
                document.getElementById('auth-modal').classList.add('hidden');

                const data = await res.json();

                document.getElementById('val-total-launches').innerText = data.total_launches.toLocaleString();
                document.getElementById('val-total-sessions').innerText = data.total_sessions.toLocaleString();

                const totalTraffic = data.socks_count + data.direct_count;
                const socksPct = totalTraffic > 0 ? Math.round((data.socks_count / totalTraffic) * 100) : 0;
                document.getElementById('val-socks-rate').innerText = socksPct + '%';
                document.getElementById('val-socks-counts').innerText = `${data.direct_count} direct / ${data.socks_count} socks`;

                const sortedMc = sortMapEntries(data.mc_versions);
                const topMcEl = document.getElementById('val-top-mc');
                const topMcCard = document.getElementById('card-top-mc');

                if (sortedMc.length > 0) {
                    const topName = sortedMc[0][0];
                    const topCount = sortedMc[0][1];
                    const topPct = data.total_launches > 0 ? Math.round((topCount / data.total_launches) * 100) : 0;
                    const topColor = getDeterministicColor(topName);

                    topMcEl.innerText = topName;
                    topMcEl.style.color = topColor;
                    topMcCard.style.borderColor = topColor + '66';
                    document.getElementById('val-top-mc-pct').innerText = `${topCount} launches (${topPct}%)`;
                } else {
                    topMcEl.innerText = 'N/A';
                    topMcEl.style.color = '#94a3b8';
                }

                updateDoughnut('chart-mc', data.mc_versions);
                updateBar('chart-mod', data.mod_versions);
                updateDoughnut('chart-os', data.os_breakdown);
                updateDurationBar('chart-duration', data.duration_buckets);
                updateTrend('chart-trend', data.daily_trend);
            } catch (err) {
                console.error('Failed to load stats:', err);
            }
        }

        function updateDoughnut(id, dataMap) {
            const sorted = sortMapEntries(dataMap);
            const labels = sorted.map(e => e[0]);
            const values = sorted.map(e => e[1]);
            const colors = labels.map(l => getDeterministicColor(l));

            if (charts[id]) {
                charts[id].data.labels = labels;
                charts[id].data.datasets[0].data = values;
                charts[id].data.datasets[0].backgroundColor = colors;
                charts[id].update();
                return;
            }

            const ctx = document.getElementById(id).getContext('2d');
            charts[id] = new Chart(ctx, {
                type: 'doughnut',
                data: {
                    labels: labels,
                    datasets: [{
                        data: values,
                        backgroundColor: colors,
                        borderColor: '#1e293b',
                        borderWidth: 2
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 400 },
                    plugins: { legend: { position: 'bottom', labels: { color: '#cbd5e1' } } }
                }
            });
        }

        function updateBar(id, dataMap) {
            const sorted = sortMapEntries(dataMap);
            const labels = sorted.map(e => e[0]);
            const values = sorted.map(e => e[1]);
            const colors = labels.map(l => getDeterministicColor(l));

            if (charts[id]) {
                charts[id].data.labels = labels;
                charts[id].data.datasets[0].data = values;
                charts[id].data.datasets[0].backgroundColor = colors;
                charts[id].update();
                return;
            }

            const ctx = document.getElementById(id).getContext('2d');
            charts[id] = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [{
                        label: 'Count',
                        data: values,
                        backgroundColor: colors,
                        borderRadius: 6
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 400 },
                    plugins: { legend: { display: false } },
                    scales: {
                        x: { ticks: { color: '#94a3b8' }, grid: { display: false } },
                        y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } }
                    }
                }
            });
        }

        function updateDurationBar(id, dataMap) {
            const bucketOrder = ['under_5m', '5m_15m', '15m_1h', '1h_3h', 'over_3h'];
            const labels = ['< 5m', '5-15m', '15m-1h', '1-3h', '> 3h'];
            const counts = bucketOrder.map(b => dataMap[b] || 0);
            const colors = ['#38bdf8', '#34d399', '#facc15', '#fb923c', '#a78bfa'];

            if (charts[id]) {
                charts[id].data.datasets[0].data = counts;
                charts[id].update();
                return;
            }

            const ctx = document.getElementById(id).getContext('2d');
            charts[id] = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: labels,
                    datasets: [{
                        label: 'Sessions',
                        data: counts,
                        backgroundColor: colors,
                        borderRadius: 6
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 400 },
                    plugins: { legend: { display: false } },
                    scales: {
                        x: { ticks: { color: '#94a3b8' }, grid: { display: false } },
                        y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } }
                    }
                }
            });
        }

        function updateTrend(id, trendList) {
            const labels = trendList.map(t => t.date);
            const launches = trendList.map(t => t.launches);
            const sessions = trendList.map(t => t.sessions);

            if (charts[id]) {
                charts[id].data.labels = labels;
                charts[id].data.datasets[0].data = launches;
                charts[id].data.datasets[1].data = sessions;
                charts[id].update();
                return;
            }

            const ctx = document.getElementById(id).getContext('2d');
            charts[id] = new Chart(ctx, {
                type: 'line',
                data: {
                    labels: labels,
                    datasets: [
                        {
                            label: 'Launches',
                            data: launches,
                            borderColor: '#38bdf8',
                            backgroundColor: 'rgba(56, 189, 248, 0.1)',
                            fill: true,
                            tension: 0.3
                        },
                        {
                            label: 'Voice Sessions',
                            data: sessions,
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
                    animation: { duration: 400 },
                    plugins: { legend: { position: 'top', labels: { color: '#cbd5e1' } } },
                    scales: {
                        x: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } },
                        y: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } }
                    }
                }
            });
        }

        // Init
        loadStats();

        // Smooth countdown timer and auto-refresh every 30s
        setInterval(() => {
            countdown--;
            if (countdown <= 0) {
                countdown = 30;
                loadStats();
            }
            const timerEl = document.getElementById('timer-text');
            if (timerEl) {
                timerEl.innerText = `Auto-refresh in ${countdown}s`;
            }
        }, 1000);
    </script>
</body>
</html>"#)
}
