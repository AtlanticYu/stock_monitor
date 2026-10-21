import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from migrate_sqlite import convert

class MigrationTest(unittest.TestCase):
    def test_reads_original_schema_without_modifying_source(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'desktop.sqlite3'
            db = sqlite3.connect(path)
            db.executescript('''
CREATE TABLE instruments(id INTEGER PRIMARY KEY,market TEXT,exchange TEXT,code TEXT,name TEXT,page_url TEXT,currency TEXT,enabled INTEGER,sort_order INTEGER,created_at INTEGER,updated_at INTEGER);
INSERT INTO instruments VALUES(7,'us','US','AAPL','Apple','https://finance.baidu.com/stock/us-aapl','USD',1,0,1700000000,1700000000);
CREATE TABLE bars(instrument_id INTEGER,period TEXT,timestamp INTEGER,open REAL,high REAL,low REAL,close REAL,volume REAL,source TEXT,adjustment TEXT,fetched_at INTEGER);
INSERT INTO bars VALUES(7,'day',1700000000,100,110,90,105,NULL,'百度历史接口','unknown',1700000000123);
CREATE TABLE intraday_bars(instrument_id INTEGER,trading_date TEXT,timestamp INTEGER,open REAL,high REAL,low REAL,close REAL,volume REAL,source TEXT,fetched_at INTEGER);
INSERT INTO intraday_bars VALUES(7,'2023-11-14',1700000100,105,105,105,105,500,'百度分时',1700000100123);
''')
            db.commit()
            db.close()
            before = path.read_bytes()
            archive = convert(path)
            self.assertEqual(before, path.read_bytes())
            self.assertEqual('stocks-monitor-platform', archive['format'])
            self.assertEqual(7, archive['tables']['instruments'][0]['id'])
            self.assertEqual('US', archive['tables']['instruments'][0]['exchange_name'])
            self.assertEqual(2, len(archive['tables']['bars']))
            day = next(r for r in archive['tables']['bars'] if r['period'] == 'day')
            self.assertIsNone(day['volume'])
            self.assertEqual(1700000000123, day['fetched_at'])
            self.assertEqual('2023-11-14', day['trading_date'])
            self.assertEqual(2, len(archive['tables']['series_meta']))
            json.dumps(archive, allow_nan=False)

    def test_missing_source_does_not_create_empty_database(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'missing.sqlite3'
            with self.assertRaises(FileNotFoundError):
                convert(path)
            self.assertFalse(path.exists())

if __name__ == '__main__':
    unittest.main()
