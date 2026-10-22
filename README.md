# stocks_monitor_platform

`stocks_monitor` 的独立 Java Web 版本。原 Rust/Tauri 工程保留，本目录可单独复制到服务器或 Git 仓库构建。

采用 **Spring Boot 2.3.12.RELEASE、Java 8、Maven、Spring JDBC、MySQL、Flyway、Spring Security、SSE**。页面沿用原生 JavaScript、HTML/CSS 和 Canvas，运行 Java 服务不需要安装 Node、Vue 或 React，也没有 Tauri/Rust 运行时依赖。Supabase 浏览器 SDK 已打包进静态资源，只有更新 SDK 时需要 Node 和 npm。

## 启动

### Docker Compose

需要 Docker 和 Compose v2。在本目录执行：

```bash
cp .env.example .env
# 编辑 .env，填写 DB_PASSWORD、MYSQL_ROOT_PASSWORD、APP_PASSWORD。
docker compose up -d --build
docker compose logs -f app
```

默认访问 `http://127.0.0.1:8080/`，使用 `.env` 中的 `APP_USERNAME` / `APP_PASSWORD` 登录。MySQL 和应用备份目录使用持久化卷。MySQL 不对宿主机发布端口；应用默认只绑定宿主机回环地址，便于通过现有反向代理访问。

Compose 使用 MySQL 8.0 镜像；数据库建表和应用读写也已在本地 MySQL 5.7 实例验证。Docker 构建会执行 Java 测试。

### 本机 JDK / Maven

需要 JDK 8、Maven 3.6+、运行中的 MySQL 5.7/8.0。先以数据库管理员执行 `deploy/mysql-init.sql`，创建专用应用账号并授予该数据库的业务读写和 Flyway 建表权限。然后配置：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/stocks_monitor_platform?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false'
export DB_USERNAME='stocks'
read -s DB_PASSWORD
export DB_PASSWORD
export APP_USERNAME='admin'
read -s APP_PASSWORD
export APP_PASSWORD
mvn clean package
java -jar target/stocks-monitor-platform-1.0.0.jar
```

`application.yml` 仅保留通用账号 `stocks` 和空密码作为回退值；请通过 `DB_USERNAME` / `DB_PASSWORD` 提供实际连接信息，不要把本机凭据写回源码。使用 IDE 启动时，在 Run/Debug Configuration 的 Environment variables 中设置这两个变量；连接其他数据库时同时设置 `DB_URL`。

`.env` 由 Docker Compose 自动读取，IDE、`mvn spring-boot:run` 和 `java -jar` 不会自动读取它。本机启动请使用上面的 `export` 命令，或在 IDE 中配置环境变量。`.env`、`.env.*`、`.idea/`、`.vscode/`、`*.iml` 和 `.DS_Store` 已加入忽略规则；`.env.example` 作为不含真实密码的模板保留在版本管理中。

`mvn spring-boot:run` 可用于开发。首次启动自动执行 `db/migration/V1__initial_schema.sql`。未设置登录密码时，Spring Security 在启动日志中生成临时密码；部署时应在环境中提供固定密码。数据库口令、登录口令、任何部署令牌都不写入源码。

## 公司 AI 应用部署平台与 Supabase 存储

此项目仍需 Java 服务运行行情采集、技术指标、回测及 SSE；数据库可以使用本地 MySQL，或平台提供的 Supabase REST 数据空间。不能将 REST 地址填进 JDBC URL。原 MySQL 模式和 Flyway 脚本保留，默认本机启动仍使用 MySQL。

容器默认启用 `platform,supabase`，监听 `8080`，上下文路径默认 `/zhoubao`，无需配置 MySQL 密码和应用公共管理员密码。也可本机运行：

```bash
java -jar target/stocks-monitor-platform-1.0.0.jar --spring.profiles.active=platform,supabase
```

公开配置位于 `application-supabase.yml`，可通过 `SUPABASE_URL`、`SUPABASE_ANON_KEY`、`SUPABASE_SCHEMA` 覆盖。默认地址为平台 Supabase，schema 为 `u_yuzhengxin`；公钥按平台设计公开，数据库密码和 service-role 密钥均不使用。浏览器通过 `@supabase/supabase-js` 登录和刷新用户 session，创建客户端时指定 `db.schema`；Java 验证访问令牌后，使用该用户令牌调用 PostgREST。SSE 用携带 Authorization 的 fetch 流式请求，访问令牌不会放进 URL。

Supabase 模式使用邮箱/密码登录 Supabase Auth，不再使用原来的 `admin` 共享登录。平台工作台账号是否与 Supabase Auth 互通取决于平台配置。登录页提供按需注册按钮，不会自动创建任何测试账号。

云端表及字段见 [deploy/supabase/README.md](deploy/supabase/README.md)，建表脚本为 `deploy/supabase/schema.sql`，通过平台受限迁移工具分批执行，每批不超过 24 条语句。所有表按 `owner_id = auth.uid()` 开启并强制执行 RLS；唯一约束和关联外键也包含用户字段。后台任务、内存缓存及 SSE 推送按用户隔离。当前用户令牌过期且未续期后，不再为该用户执行定时采集。

K 线在 `stocks_series.bars` 中按“用户＋标的＋周期”保存为 JSONB 快照，源切换和复权修订以单次请求原子更新；通过 `revision` 比较并交换处理并发更新，避免半批覆盖。其他跨表操作不承诺数据库事务语义。云端整库恢复明确禁用以避免部分覆盖；保留单标的行情导入、数据导出和云端备份。MySQL 原有事务恢复逻辑不受影响。本机 MySQL 的既有数据不会自动上传。

已验证通过 `@supabase/supabase-js` 使用指定 schema 查询实际表可返回 `200 []`，匿名写入被 RLS 以 `42501` 拒绝。REST 根路径可能返回网关 `403`，应使用实际表路径检查连接。匿名公钥本身不代表用户身份，真实登录用户的读写和两个真实用户间的隔离仍需登录会话验证；不得通过关闭 RLS 或使用其他人的令牌规避。

如使用 MySQL 容器模式，显式设置 `SPRING_PROFILES_ACTIVE=platform` 并提供 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`APP_PASSWORD`。Compose 已启用这一覆盖。环境变量模板见 `deploy/platform.env.example`。

发布前确认平台代理对外部路径的处理：保留前缀时使用 `CONTEXT_PATH=/zhoubao`；若代理剥离入口前缀，则根据平台实际后端路由调整上下文。发布后检查登录跳转、静态资源、API、SSE 和实际数据库读写，不能仅以页面上传成功作为服务可用依据。

更新浏览器 SDK（已提交生成产物，日常 Maven/Docker 构建不需要 npm）：

```bash
npm ci
npm run build:supabase
```

## 路径与反向代理

可部署在域名根路径，也可部署到 `/zhoubao/`：

```bash
export CONTEXT_PATH=/zhoubao
java -jar target/stocks-monitor-platform-1.0.0.jar
```

浏览器访问 `http://服务器:8080/zhoubao/`。页面资源、API、登录和 SSE 均使用相同前缀。`deploy/nginx.conf.example` 提供保留此前缀的代理示例，并关闭 SSE 缓冲。HTTPS 代理环境设置 `COOKIE_SECURE=true`；外部流量应通过配置好的代理入口访问。

## 已实现功能

| 功能 | Web 版实现 |
| --- | --- |
| 港股、沪深 A 股、美股股票列表 | MySQL / Supabase 存储，添加、编辑名称、启停、排序、切换与删除 |
| 行情快照 | 服务端订阅百度 WebSocket，推送价格、盘口及逐笔字段；缺失字段保留为空 |
| 历史行情与图表 | 服务端请求百度历史接口，美股/港股/沪深 A 股日 K 支持腾讯备用源；分时/日/周/月/年 K、日线聚合、Canvas 图表、均线与缩放 |
| 数据采集失败 | 显示来源错误，冷却 5 分钟，保留已有缓存；不伪造行情 |
| 行情导入导出 | 浏览器上传 JSON，下载单只股票的行情 JSON |
| 技术指标 | MA、EMA、WMA、DEMA、TEMA、HMA、MACD、RSI、KDJ、STOCH、WILLR、CCI、ROC、MOM、TRIX、PPO、ADX、AROON、ATR、布林带、Keltner、Donchian、HV、SAR、成交量/资金流、Ichimoku、形态等 |
| 指标说明 | 沿用原中文指标目录、公式、适用条件、局限及预热空值 |
| 综合分析与风险 | 日线趋势/动量/RSI 组合评分、20 根支撑阻力、ATR/RSI 风险描述；有效分量权重归一化 |
| 应用内提醒 | 信号变化、评分变化、RSI 阈值和成交量倍数，支持开关、冷却时间、历史记录及已读 |
| 单股量化回测 | MA / MACD / RSI / Bollinger 四策略，日期区间、预热、下一根开盘成交、交易成本、整股/碎股、价格限制、已平仓与未平仓区分 |
| 单股筛选和参数扫描 | 同策略比较自选股票，按历史已完成交易胜率排序；支持候选参数列表 |
| 组合研究 | 页面可选择研究币种，同币种与交易市场校验、多因子横截面标准化打分、目标持仓数、单股仓位上限、日/周/月/季再平衡、费用、已知成交量参与率、资金曲线和持仓 |
| 研究指标 | 总收益、CAGR、波动率、最大回撤、Sharpe、Sortino、Calmar、基准/超额收益；基准完整时计算 Beta/Alpha，零分母时为空 |
| 研究管理 | 保存/查询/比较研究结果；参数比较与滚动训练/验证通过 API 调用 |
| 数据管理 | 逻辑 JSON 备份下载/恢复、恢复前自动备份、保留期限、手动清理、诊断下载 |
| Web 身份验证 | 表单登录、HTTP Basic、Session、CSRF 校验；业务 API 均需要认证 |

页面是单个共享工作空间：当前选中股票、数据和研究记录对该实例的登录会话共享。没有新增多租户账号体系、券商实盘下单或自动交易。

### 与桌面版的边界和变化

1. 浏览器通过 HTTP + SSE 连接 Java 服务。最新报价使用 WebSocket，历史 K 线使用独立的 HTTP 接口。百度历史接口使用数字周期参数（`ktype=1/2/3/4` 对应日/周/月/年）。百度返回 HTTP 403、验证码、网络错误或无有效历史时，美股、港股和沪深 A 股的日 K 自动尝试腾讯备用源；请求最近 640 根，数据源可能附带当日未完成 K 线。美股按元数据解析交易所后缀，港股保留五位代码前导零，`ab-688825` / `ab-000001` 等明确的沪深股票代码自动映射交易所；无法确定交易所的代码不猜测。图表标明来源、复权口径和更新时间。A 股成交量按同次返回的报价、成交额及高低价核对“股/手”单位并统一为股，无法确认时留空。不同来源或复权口径不混合存储。

   日 K 和分时分别处理同步结果；分时仍依赖百度。失败原因会持续显示并保留已有数据，有部分有效数据时标记“部分行情受限”。服务端不运行隐藏 WebView，浏览器打开百度原页无法替服务端完成验证码。也可迁移桌面数据或上传真实行情 JSON。
2. 技术指标与单股回测保留原计算口径，并使用 Rust 导出的固定样本比对。综合评分在 Web 版采用可解释的 MA20 40% + MACD 35% + RSI14 25% 有效权重归一口径；它是描述性分数，不能视为盈利概率，也不与旧版评分直接比较。
3. 组合引擎标为 **v2**：拒绝无汇率的跨币种混合；同市场按共同交易日期对齐；开盘成交量限制使用前一日已知成交量，修正旧版当日全日成交量的前视；单次再平衡未成交余量失效，整股余量不会阻止下一次再平衡。组合结果与 Rust v1 不保证逐笔一致。
4. 组合默认 60% / 20% / 20% 是同一预设策略曲线的分段统计。`run_walk_forward` 才会按训练区间选择周/月再平衡配置，再独立模拟后续验证区间；各窗口独立资金，不拼接为一条收益曲线。普通组合研究不能称为模型训练。
5. 组合模拟没有企业行动自动修复、外汇换算、完整交易所节假日库或每只股票的特殊涨跌停规则。默认市场规则只是可配置模型，例如港股手数应按真实标的修改。数据来源与复权口径应在研究前核实。
6. MySQL 模式下，备份是数据库业务表的逻辑备份，不是原 SQLite 文件或 MySQL 物理备份。页面不接受服务器文件路径。恢复替换当前业务表，先校验格式并保存原数据，写入使用事务。大数据量生产备份可另外使用 MySQL 运维工具。

## 导入行情

先添加股票，如 `hk-00700`、`sh-600519`、`sz-000001`、`ab-688825` 或 `us-AAPL`，也支持百度指数页，例如 `https://finance.baidu.com/index/us-IXIC`（纳斯达克综合指数）、`index/us-DJI`、`index/us-INX`、`index/hk-HSI` 或 `index/sh-000001`。指数请保留 `/index/` 路径，以便实时订阅和历史请求使用 `financeType=index`；编辑时不能切换股票/指数类型。已适配 IXIC、DJI、INX 的腾讯日 K 备用源；不支持备用映射的指数沿用百度数据源，验证码或请求失败时保留缓存并显示原因。然后在“设置”中选择 JSON 文件并点击“导入当前股票行情”。

```json
{
  "period": "day",
  "adjustment": "raw",
  "bars": [
    { "time": 1700000000, "open": 100, "high": 110, "low": 90, "close": 105, "volume": 10000 }
  ]
}
```

`time` 使用 Unix **秒**；`volume` 缺失使用 `null`，不补 0。`period` 为 `intraday/day/week/month/year`；价格口径 `adjustment` 为 `unknown/raw/qfq/hfq`。每批最多 20,000 根，HTTP JSON 请求最大 16 MB。批内任一无效 OHLCV 会拒绝整批，保留旧数据。切换来源或复权口径，以及发生历史价格修订时，会更换对应周期数据，避免混合口径。普通末根 K 线更新保留前序历史。

导出的单只股票 JSON 可直接重新导入其中的 `series`。导入来源统一显示“用户导入”，不冒充已核实的行情供应商数据。

## 迁移原 SQLite

在停止桌面版写入、取得一致的 SQLite 文件后，用 Python 3.9+ 执行：

```bash
python3 scripts/migrate_sqlite.py /绝对路径/stock-research.sqlite3 /绝对路径/stocks-web-archive.json
```

脚本只读 SQLite，输出 Web 版备份格式，包含股票、历史日/分钟线、最新报价、市场规则、策略、历史回测、组合结果与提醒。保留原结果版本，不把旧组合结果重新标为 v2。旧技术指标缓存、旧分析快照和组合的拆分明细表不单独迁移，指标可重算、组合明细保留在已有结果 JSON 中。脚本不会覆盖已有输出文件。

然后在 Web 设置中选择生成的文件并恢复。**恢复会替换目标数据库业务数据**，请在目标空库或确认可替换时使用。16 MB 以上的存量可通过管理员离线导入，或分只使用行情导入 API；当前浏览器恢复上限为 16 MB。

## API

- `GET /api/session`：登录用户与当前 CSRF header/token。
- `POST /api/commands/{command}`：原工作台命令的 HTTP 入口；例如 `list_instruments`、`run_backtest`、`run_portfolio_research`、`validate_research_data`、`run_walk_forward`。
- `GET /api/events`：SSE，事件包括 `quote-update`、`quote-status`、`quote-health`、`instrument-changed`、`chart-data-updated`、`realtime-analysis-update`、`analysis-event`。
- `POST /api/instruments/{id}/series`：导入某只股票的行情。
- `GET /api/instruments/{id}/export`：行情导出。
- `GET /api/archive`：业务备份下载。
- `POST /api/archive`：multipart 字段 `file` 恢复备份。
- `GET /api/diagnostics`：诊断下载，不包含密码和连接凭据。
- `GET /actuator/health`：公开的简要存活/数据库健康状态。

写请求需要登录 Cookie 或 HTTP Basic，同时携带 `/api/session` 返回的 CSRF header/token。命令请求参数保持 camelCase，例如：

```json
{
  "request": {
    "instrumentId": 1,
    "strategy": "ma",
    "parameters": { "fastPeriod": 5, "slowPeriod": 20 },
    "initialCapital": 100000,
    "maxPosition": 1,
    "startDate": "2024-01-01",
    "endDate": "2024-12-31"
  }
}
```

`run_walk_forward` 请求为 `{ "request": { "base": { ...组合请求... }, "trainWindow": 504, "validationWindow": 126, "step": 63 } }`，最多 24 个窗口。单股参数扫描最多 64 组。耗时计算是同步请求，大样本应给代理配置足够超时。

## 验证

```bash
mvn test
python3 scripts/test_migrate_sqlite.py
# 可选：前端刷新/切换回归验证（Node.js 18+，不影响 Java 服务运行）
node scripts/test_chart_reload.cjs
node scripts/test_selection.cjs
# 对已运行的服务进行验证；先在环境中设置 APP_PASSWORD。
# 此脚本创建有明显标记的合成样本，验证完成后删除该股票及其回测记录。
python3 scripts/smoke_web.py http://127.0.0.1:8080/
# 使用子路径时：
python3 scripts/smoke_web.py http://127.0.0.1:8080/zhoubao/
```

31 项 Java 测试和 2 项 Python 迁移测试通过。Java 测试包括 Rust 技术指标数值和空值对齐、4 策略×2 日期区间的 Rust 回测收益/成交/曲线对齐、整股余量回归、币种/价格口径校验、资金守恒、前视成交量检查、行情解析/验证码/URL 校验、数字 K 线周期、日 K/分时失败隔离与错误恢复、备用源美股/港股/沪深市场映射、港股前导零、A 股成交量单位、股票代码及复权口径校验、登录与 CSRF、持久化和备份恢复。前端回归另覆盖同股刷新保留图表、加载失败保留图表、切换股票清空旧图忽略其他股票事件，以及启动旧响应不覆盖当前选中股票。H2 的 MySQL 模式仅用于自动化测试。运行时按配置连接 MySQL 或 Supabase REST。Supabase 新增测试覆盖用户令牌、schema/owner 条件、分页、失败不泄露凭据、用户隔离、并发行情快照和无 JDBC 启动。真实云端用户读写仍需完成平台认证后验证。

本地还进行了真实 MySQL 5.7 的 Flyway 建表、打包 JAR 启动、`/zhoubao/` 下的浏览器操作验证。还在独立 H2 环境中验证了 Java 真实网络请求自动同步 NVDA、港股 01810、沪市 688825（通用 ab 代码）和深市 000001，并验证浏览器绘图和股票切换。Docker Compose 文件已提供；当前环境没有运行中的 Docker 服务，未执行容器镜像构建。

## 目录

```text
src/main/java/com/soyoung/stocks/
  config/       登录、CSRF 与请求大小限制
  domain/       行情、股票、回测请求模型
  repository/   MySQL / Supabase 数据读写
  service/      指标、单股回测、组合研究、采集、SSE、备份与命令路由
  web/          HTTP API
src/main/resources/
  db/migration/ Flyway SQL
  static/       浏览器工作台
  application.yml
src/test/       Rust 样本对照与集成测试
scripts/        SQLite 迁移与运行中服务验证
deploy/         数据库初始化及 Nginx 示例
```
# stock_monitor
# stock_monitor
