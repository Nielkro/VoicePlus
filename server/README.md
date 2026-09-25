# VoicePlus Anonymous Telemetry Server

Легковесный бэкенд на **Rust (Axum + SQLite)** со встроенным веб-дашбордом для анонимной аналитики мода VoicePlus.

## Особенности
* 🛡️ **100% GDPR-чистый и анонимный:** Не сохраняет IP-адреса, реальные UUID игроков, никнеймы или постоянные ID устройств.
* 📊 **Встроенный веб-дашборд:** Нативный интерфейс (Tailwind CSS + Chart.js) со статистикой версий Minecraft, версий мода, времени сессий и графиком запусков за 14 дней.
* ⚡ **Сверхбыстрый и компактный:** Потребляет ~5–10 МБ RAM.
* 🔒 **Интеграция с Caddy:** Готовый `Caddyfile` с отключенным логированием IP (`output discard`).

---

## Запуск и сборка на VPS

### 1. Сборка бинарника
```bash
cd server
cargo build --release
```
Бинарник появится в `target/release/voiceplus-telemetry-server`.

### 2. Запуск вручную
```bash
HOST=127.0.0.1 PORT=8080 ./target/release/voiceplus-telemetry-server
```

### 3. Настройка Caddy
Добавьте в ваш `/etc/caddy/Caddyfile`:
```caddy
stats.yourdomain.com {
    reverse_proxy 127.0.0.1:8080
    log {
        output discard
    }
    encode gzip zstd
}
```
Перезапустите Caddy:
```bash
sudo systemctl reload caddy
```

### 4. Автозапуск через Systemd (опционально)
```bash
sudo mkdir -p /opt/voiceplus-telemetry
sudo cp target/release/voiceplus-telemetry-server /opt/voiceplus-telemetry/
sudo cp voiceplus-telemetry.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now voiceplus-telemetry
```

---

## Доступ к дашборду
Откройте в браузере: `https://stats.yourdomain.com/`
API статистики: `https://stats.yourdomain.com/api/v1/stats`
Прием метрик: `POST https://stats.yourdomain.com/api/v1/metrics`
