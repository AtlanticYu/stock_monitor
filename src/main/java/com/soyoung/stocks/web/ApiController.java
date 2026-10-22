package com.soyoung.stocks.web;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.StockRepository;
import com.soyoung.stocks.service.*;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final CommandService commands;
  private final EventHub hub;
  private final StockRepository db;
  private final ArchiveService archives;
  private final AnalysisService analysis;

  public ApiController(
      CommandService commands,
      EventHub hub,
      StockRepository db,
      ArchiveService archives,
      AnalysisService analysis) {
    this.commands = commands;
    this.hub = hub;
    this.db = db;
    this.archives = archives;
    this.analysis = analysis;
  }

  @GetMapping("/session")
  public Object session(HttpServletRequest request) {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return map(
        "username",
        request.getUserPrincipal().getName(),
        "csrfHeader",
        token == null ? null : token.getHeaderName(),
        "csrfToken",
        token == null ? null : token.getToken());
  }

  @PostMapping("/commands/{name}")
  public Object command(
      @PathVariable String name, @RequestBody(required = false) Map<String, Object> args) {
    return MAPPER.valueToTree(commands.execute(name, args == null ? Collections.emptyMap() : args));
  }

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events() {
    return hub.subscribe();
  }

  @PostMapping("/instruments/{id}/series")
  public Object importSeries(@PathVariable long id, @RequestBody Series series) {
    Instrument i = db.instrument(id);
    if (series.bars == null || series.bars.isEmpty() || series.bars.size() > 20000)
      throw new IllegalArgumentException("每批导入 1 至 20000 根 K 线");
    if (!Arrays.asList("unknown", "qfq", "hfq", "raw").contains(series.adjustment))
      throw new IllegalArgumentException("价格口径使用 unknown/qfq/hfq/raw");
    series.source = "用户导入";
    series.note = "用户提供的数据，来源与公司行动口径需自行核实";
    series.fetchedAtMs = System.currentTimeMillis();
    db.saveSeries(i, series);
    hub.emit("chart-data-updated", map("instrumentId", id, "period", series.period));
    analysis.publish(i);
    return map("imported", series.bars.size(), "instrumentId", id);
  }

  @GetMapping("/archive")
  public ResponseEntity<byte[]> archive() {
    return download("stocks-backup.json", archives.export());
  }

  @PostMapping(value = "/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object restore(@RequestParam("file") MultipartFile file) throws Exception {
    if (file.getSize() > 16 * 1024 * 1024) throw new IllegalArgumentException("备份不能超过 16 MB");
    return map(
        "backup",
        archives.restore(MAPPER.readValue(file.getInputStream(), Map.class)),
        "message",
        "恢复完成，原数据已备份到服务端");
  }

  @GetMapping("/diagnostics")
  public ResponseEntity<byte[]> diagnostics() {
    return download(
        "stocks-diagnostics.json", commands.execute("export_diagnostics", Collections.emptyMap()));
  }

  @GetMapping("/instruments/{id}/export")
  public ResponseEntity<byte[]> export(@PathVariable long id) {
    return download(
        "stocks-" + id + ".json",
        commands.execute("export_instrument_data", map("instrumentId", id)));
  }

  private ResponseEntity<byte[]> download(String name, Object value) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .body(write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.time.DateTimeException.class})
  public ResponseEntity<Object> bad(Exception e) {
    return ResponseEntity.badRequest().body(map("message", e.getMessage()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Object> conflict(Exception e) {
    return ResponseEntity.status(409).body(map("message", "记录重复或数据不符合数据库约束"));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  public ResponseEntity<Object> invalidBody(Exception e) {
    return ResponseEntity.badRequest().body(map("message", "请求内容无效，请检查 JSON 字段和上传文件"));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<Object> tooLarge(Exception e) {
    return ResponseEntity.status(413).body(map("message", "文件不能超过16MB"));
  }

  @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
  public void disconnectedClient() {
    /* Browser closed a streaming response. */
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> failure(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass()).error("API failure", e);
    return ResponseEntity.status(500).body(map("message", "服务端处理失败，请查看服务日志"));
  }
}
