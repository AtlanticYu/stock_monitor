# 股票工作台云端表

平台 schema：`u_yuzhengxin`。以下 10 张表已通过 ai-deploy 创建；全部启用并强制执行 RLS。所有表共同拥有 `owner_id UUID NOT NULL DEFAULT auth.uid()`，使用 `owner_id = auth.uid()` 同时检查读取、写入和修改后的归属；匿名请求不能读取其他用户数据或写入数据。不要把匿名公钥当作用户 access token。

| 表 | 除 owner_id 外的字段 | 主键 / 唯一约束 |
| --- | --- | --- |
| `stocks_instruments` | `id bigint identity`, `market varchar(12)`, `exchange_name varchar(32)`, `code varchar(24)`, `name varchar(128)`, `page_url varchar(512)`, `currency varchar(8)`, `enabled boolean`, `sort_order integer`, `name_source varchar(16)`, `created_at bigint`, `updated_at bigint`, `name_updated_at bigint nullable` | 主键 `(owner_id,id)`；唯一 `(owner_id,market,code)` |
| `stocks_settings` | `setting_key varchar(80)`, `setting_value text` | 主键 `(owner_id,setting_key)` |
| `stocks_series` | `instrument_id bigint`, `period varchar(12)`, `source text`, `adjustment varchar(128)`, `fetched_at bigint`, `previous_close double precision nullable`, `note text`, `bars jsonb`, `revision uuid` | 主键 `(owner_id,instrument_id,period)` |
| `stocks_quotes` | `instrument_id bigint`, `received_at bigint`, `payload jsonb` | 主键 `(owner_id,instrument_id)` |
| `stocks_strategies` | `id bigint identity`, `name varchar(128)`, `kind varchar(32)`, `parameters_json jsonb`, `created_at bigint` | 主键 `(owner_id,id)`；唯一 `(owner_id,name)` |
| `stocks_backtests` | `id bigint identity`, `instrument_id bigint`, `strategy varchar(32)`, `parameters_json jsonb`, `request_json jsonb`, `result_json jsonb`, `created_at bigint` | 主键 `(owner_id,id)` |
| `stocks_research_runs` | `id bigint identity`, `request_json jsonb`, `result_json jsonb`, `calculation_version integer`, `created_at bigint` | 主键 `(owner_id,id)` |
| `stocks_alert_profiles` | `instrument_id bigint`, `payload jsonb` | 主键 `(owner_id,instrument_id)` |
| `stocks_analysis_events` | `id bigint identity`, `instrument_id bigint`, `event_time bigint`, `acknowledged boolean`, `payload jsonb` | 主键 `(owner_id,id)` |
| `stocks_backups` | `id bigint identity`, `name text`, `created_at bigint`, `payload jsonb` | 主键 `(owner_id,id)` |

关联标的的表均使用复合外键 `(owner_id,instrument_id)` 指向 `stocks_instruments(owner_id,id)`，删除标的时级联清理，防止引用其他用户标的。字段完整约束见 `schema.sql`。

`stocks_series.bars` 保存按时间排序的 K 线数组，每个元素为 `{time, open, high, low, close, volume}`。`time` 为秒级时间戳；`volume` 可以为 null。`fetched_at` 和 `received_at` 为毫秒级时间戳；`created_at`、`updated_at`、`event_time` 为秒级时间戳。

## 与 MySQL 表的对应

- `instruments` → `stocks_instruments`
- `app_settings` / 市场规则设置 → `stocks_settings`（市场规则键名 `rules:市场`）
- `series_meta` + `bars` → `stocks_series`（一次原子写入一整个周期的快照）
- 其余业务表使用 `stocks_` 前缀，对应同名用途；新增 `stocks_backups` 存云端备份
- Supabase 不创建 `flyway_schema_history`，由平台迁移工具管理 DDL

## 验证状态与限制

建表工具已确认这些表存在且启用 RLS；本地自动化测试覆盖 Java 请求中的 schema、用户 token、owner 条件、SSE 用户隔离以及 MySQL 回归。实际表路径已验证匿名查询返回 `200 []`，匿名写入返回 `401` / PostgreSQL `42501`（RLS 拒绝）。使用 `@supabase/supabase-js` 指定 schema 的查询也已实测通过。先前 REST 根路径的 `403` 不代表表路径不可用。真实登录用户的数据读写及两个真实用户间的 RLS 行为仍需真实登录会话验证。没有使用 service-role 密钥、没有关闭 RLS、没有创建测试用户，也没有上传本机 MySQL 数据。

Supabase 的整个周期行情保存使用 `revision` 条件更新处理竞争，单次写入由 PostgREST 保证原子性。跨表操作、排序和批量清理不具备 MySQL 的跨表事务。整库恢复在云端模式明确拒绝执行；可使用单标的行情导入和 JSON 导出。不要将云端备份交给 MySQL 恢复器，两种备份格式不同。
