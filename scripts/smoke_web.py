#!/usr/bin/env python3
"""Smoke test a running Web server. Creates synthetic test data, then removes it.
APP_USERNAME and APP_PASSWORD are read only from environment, never printed.
Usage: python3 scripts/smoke_web.py http://127.0.0.1:8080/
"""
import http.cookiejar
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base = sys.argv[1].rstrip('/') + '/' if len(sys.argv) > 1 else 'http://127.0.0.1:8080/'
username = os.getenv('APP_USERNAME', 'admin')
password = os.environ['APP_PASSWORD']
cookies = http.cookiejar.CookieJar()
http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

def request(path, value=None, csrf=None):
    headers = {}
    if value is not None:
        headers['Content-Type'] = 'application/json'
    if csrf:
        headers[csrf['csrfHeader']] = csrf['csrfToken']
    body = None if value is None else json.dumps(value).encode()
    with http.open(urllib.request.Request(base + path, data=body, headers=headers), timeout=30) as response:
        return json.load(response)

with http.open(base + 'login', timeout=15) as response:
    html = response.read().decode()
token = re.search(r'name="_csrf"[^>]*value="([^"]+)"', html).group(1)
form = urllib.parse.urlencode(dict(username=username, password=password, _csrf=token)).encode()
with http.open(urllib.request.Request(base + 'login', data=form), timeout=15) as response:
    response.read()
session = request('api/session')

def command(name, **args):
    return request('api/commands/' + name, args, session)

assert isinstance(command('database_path'), str)
assert command('check_database_integrity') == 'ok'
original = command('get_active_instrument')
created = None
try:
    code = 'us-TEST' + str(time.time_ns())[-12:]
    created = command('add_instrument', pageUrl=code, displayName='自动验证用合成样本（非行情）')
    command('set_active_instrument', id=created['id'])
    bars = []
    for k in range(260):
        close = 90 + k * .1 + math.sin(k / 4.7) * 5
        bars.append(dict(time=1700000000 + k * 86400, open=close, high=close + 2,
                         low=close - 2, close=close, volume=10000))
    result = request(f"api/instruments/{created['id']}/series", dict(period='day', adjustment='raw', bars=bars), session)
    assert result['imported'] == 260
    chart = command('load_chart', instrumentId=created['id'], period='day')
    assert len(chart['series']['bars']) == 260
    indicators = command('load_analysis', instrumentId=created['id'], period='day', request={})
    assert len(indicators['indicatorDetails']) >= 35
    backtest = command('run_backtest', request=dict(instrumentId=created['id'], strategy='ma'))
    assert backtest['dataQuality'] == 'valid'
    history = command('list_backtests', instrumentId=created['id'])
    assert any(row['id'] == backtest['id'] for row in history)
    with http.open(base + 'api/events', timeout=10) as response:
        first = response.readline().decode().strip()
        assert first == 'event:connected', first
    print('通过：登录、CSRF、行情导入、MySQL 读取、技术指标、回测持久化、SSE 连接')
finally:
    if created:
        command('delete_instrument', id=created['id'])
    if original:
        command('set_active_instrument', id=original['id'])
