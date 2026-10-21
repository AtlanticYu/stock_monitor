CREATE TABLE instruments (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, market VARCHAR(12) NOT NULL,
 exchange_name VARCHAR(32) NOT NULL, code VARCHAR(24) NOT NULL, name VARCHAR(128) NOT NULL,
 page_url VARCHAR(512) NOT NULL, currency VARCHAR(8) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 sort_order INT NOT NULL DEFAULT 0, name_source VARCHAR(16) NOT NULL DEFAULT 'auto',
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, name_updated_at BIGINT,
 UNIQUE KEY uk_instrument(market,code)
);
CREATE TABLE app_settings (setting_key VARCHAR(80) PRIMARY KEY, setting_value LONGTEXT NOT NULL);
CREATE TABLE series_meta (
 instrument_id BIGINT NOT NULL, period VARCHAR(12) NOT NULL, source VARCHAR(128) NOT NULL,
 adjustment VARCHAR(128) NOT NULL, fetched_at BIGINT NOT NULL, previous_close DOUBLE,
 note VARCHAR(1024), PRIMARY KEY(instrument_id,period),
 CONSTRAINT fk_meta_instrument FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
);
CREATE TABLE bars (
 instrument_id BIGINT NOT NULL, period VARCHAR(12) NOT NULL, bar_time BIGINT NOT NULL,
 trading_date DATE NOT NULL, open_price DOUBLE NOT NULL, high_price DOUBLE NOT NULL,
 low_price DOUBLE NOT NULL, close_price DOUBLE NOT NULL, volume DOUBLE,
 source VARCHAR(128) NOT NULL, adjustment VARCHAR(128) NOT NULL, fetched_at BIGINT NOT NULL,
 previous_close DOUBLE, PRIMARY KEY(instrument_id,period,bar_time),
 KEY idx_bars_date(instrument_id,period,trading_date),
 CONSTRAINT fk_bar_instrument FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
);
CREATE TABLE quotes (
 instrument_id BIGINT PRIMARY KEY, received_at BIGINT NOT NULL, payload LONGTEXT NOT NULL,
 CONSTRAINT fk_quote_instrument FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
);
CREATE TABLE strategies (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(128) NOT NULL UNIQUE,
 kind VARCHAR(32) NOT NULL, parameters_json LONGTEXT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE backtests (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, instrument_id BIGINT NOT NULL,
 strategy VARCHAR(32) NOT NULL, parameters_json LONGTEXT NOT NULL, request_json LONGTEXT NOT NULL,
 result_json LONGTEXT NOT NULL, created_at BIGINT NOT NULL,
 KEY idx_backtest_instrument(instrument_id,created_at),
 CONSTRAINT fk_backtest_instrument FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
);
CREATE TABLE research_runs (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, request_json LONGTEXT NOT NULL,
 result_json LONGTEXT NOT NULL, calculation_version INT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE market_rules (market VARCHAR(12) PRIMARY KEY, payload LONGTEXT NOT NULL);
CREATE TABLE alert_profiles (
 instrument_id BIGINT PRIMARY KEY, payload LONGTEXT NOT NULL,
 CONSTRAINT fk_alert_instrument FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
);
CREATE TABLE analysis_events (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, instrument_id BIGINT NOT NULL,
 event_time BIGINT NOT NULL, acknowledged BOOLEAN NOT NULL DEFAULT FALSE, payload LONGTEXT NOT NULL,
 KEY idx_event_instrument(instrument_id,event_time),
 CONSTRAINT fk_event_instrument FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
);
