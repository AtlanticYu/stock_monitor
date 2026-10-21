#!/usr/bin/env python3
"""Read a desktop SQLite DB without changing it; emit a Web archive for UI restore.
Python 3.9+. No network, passwords, or third-party packages are required.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import sqlite3
from zoneinfo import ZoneInfo

TABLES = ('instruments', 'app_settings', 'series_meta', 'bars', 'quotes', 'strategies',
          'backtests', 'research_runs', 'market_rules', 'alert_profiles', 'analysis_events')

def convert(source):
    source = Path(source).resolve(strict=True)
    db = sqlite3.connect(source.as_uri() + '?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    known = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    def rows(table):
        return [dict(r) for r in db.execute('SELECT * FROM ' + table)] if table in known else []
    output = {t: [] for t in TABLES}
    instruments = {r['id']: r for r in rows('instruments')}
    if not instruments:
        raise ValueError('数据库没有 instruments 数据，确认这是桌面版股票数据库')
    for r in instruments.values():
        output['instruments'].append(dict(id=r['id'], market=r['market'], exchange_name=r['exchange'],
            code=r['code'], name=r['name'], page_url=r['page_url'], currency=r['currency'],
            enabled=bool(r['enabled']), sort_order=r['sort_order'], name_source=r.get('name_source', 'custom'),
            created_at=r['created_at'], updated_at=r['updated_at'], name_updated_at=r.get('name_updated_at')))
    settings = {r['key']: r['value'] for r in rows('app_settings')}
    active = settings.get('active_instrument_id', settings.get('active_instrument', str(next(iter(instruments)))))
    output['app_settings'].append(dict(setting_key='active_instrument', setting_value=str(active)))
    if 'retention_days' in settings:
        output['app_settings'].append(dict(setting_key='retention_days', setting_value=settings['retention_days']))
    for r in rows('market_rules'):
        output['app_settings'].append(dict(setting_key='rules:' + r['market'], setting_value=r['rules_json']))
    bars = {(r['instrument_id'], r['period'], r['timestamp']): r for r in rows('bars')}
    for r in rows('intraday_bars'):
        bars[(r['instrument_id'], 'intraday', r['timestamp'])] = dict(r, period='intraday', adjustment='unknown')
    metadata = {}
    for (instrument_id, period, timestamp), r in sorted(bars.items()):
        i = instruments.get(instrument_id)
        if i is None:
            continue
        timestamp = int(timestamp)
        if timestamp > 100_000_000_000:
            timestamp //= 1000
        zone = ZoneInfo('America/New_York' if i['market'] == 'us' else 'Asia/Hong_Kong')
        date = dt.datetime.fromtimestamp(timestamp, zone).date().isoformat()
        fetched = int(r.get('fetched_at') or 0)
        if fetched < 100_000_000_000:
            fetched *= 1000
        output['bars'].append(dict(instrument_id=instrument_id, period=period, bar_time=timestamp,
            trading_date=date, open_price=r['open'], high_price=r['high'], low_price=r['low'],
            close_price=r['close'], volume=r.get('volume'), source=r.get('source') or '桌面数据库迁移',
            adjustment=r.get('adjustment') or 'unknown', fetched_at=fetched, previous_close=r.get('previous_close')))
        key = (instrument_id, period)
        if key not in metadata or fetched >= metadata[key]['fetched_at']:
            metadata[key] = dict(instrument_id=instrument_id, period=period, source=r.get('source') or '桌面数据库迁移',
                adjustment=r.get('adjustment') or 'unknown', fetched_at=fetched,
                previous_close=r.get('previous_close'), note='由 Rust 桌面 SQLite 迁移，保留原始价格口径')
    output['series_meta'] = list(metadata.values())
    latest = {}
    for r in rows('quotes'):
        key = r['instrument_id']
        if key in instruments and (key not in latest or r['received_at'] >= latest[key]['received_at']):
            latest[key] = r
    for key, r in latest.items():
        i = instruments[key]
        received = int(r['received_at'])
        if received < 100_000_000_000:
            received *= 1000
        payload = dict(instrumentId=key, code=i['code'], name=i['name'], currency=i['currency'],
            price=r.get('price'), increase=r.get('increase'), ratio=r.get('ratio'),
            marketTime=r.get('market_time'), marketStatus=r.get('market_status'), receivedAtMs=received,
            cached=True, source=r.get('source'), orderBook=json.loads(r.get('order_book_json') or '[]'),
            tickTrades=json.loads(r.get('tick_trades_json') or '[]'))
        output['quotes'].append(dict(instrument_id=key, received_at=received, payload=json.dumps(payload, ensure_ascii=False)))
    for r in rows('strategies'):
        output['strategies'].append({k: r[k] for k in ('id', 'name', 'kind', 'parameters_json', 'created_at')})
    for r in rows('backtests'):
        result = json.loads(r['result_json'])
        request = dict(instrumentId=r['instrument_id'], strategy=r['strategy'], parameters=json.loads(r['parameters_json']),
            startDate=r.get('start_date'), endDate=r.get('end_date'), initialCapital=result.get('initialCapital', 100000),
            maxPosition=1)
        output['backtests'].append(dict(id=r['id'], instrument_id=r['instrument_id'], strategy=r['strategy'],
            parameters_json=r['parameters_json'], request_json=json.dumps(request), result_json=r['result_json'], created_at=r['created_at']))
    for r in rows('research_runs'):
        result = json.loads(r['result_json'])
        output['research_runs'].append(dict(id=r['id'], request_json=r.get('portfolio_config_json') or '{}',
            result_json=r['result_json'], calculation_version=r.get('calculation_version', result.get('calculationVersion', 1)), created_at=r['created_at']))
    for r in rows('alert_profiles'):
        output['alert_profiles'].append(dict(instrument_id=r['instrument_id'], payload=r['profile_json']))
    for r in rows('analysis_events'):
        payload = dict(instrumentId=r['instrument_id'], eventTime=r['timestamp'], timestamp=r['timestamp'],
            severity=r['severity'], title=r['event_type'], message=r['message'], detail=r['message'],
            signal=r['signal'], acknowledged=bool(r['acknowledged']))
        output['analysis_events'].append(dict(id=r['id'], instrument_id=r['instrument_id'], event_time=r['timestamp'],
            acknowledged=bool(r['acknowledged']), payload=json.dumps(payload, ensure_ascii=False)))
    db.close()
    return dict(format='stocks-monitor-platform', version=1,
                exportedAtMs=int(dt.datetime.now().timestamp() * 1000), tables=output)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sqlite', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = convert(args.sqlite)
    # Refuse to overwrite an existing archive.
    with args.output.open('x', encoding='utf-8') as handle:
        json.dump(result, handle, ensure_ascii=False, allow_nan=False, separators=(',', ':'))
    print(f"已生成 {args.output}: {len(result['tables']['instruments'])} 只股票，{len(result['tables']['bars'])} 根 K 线")
