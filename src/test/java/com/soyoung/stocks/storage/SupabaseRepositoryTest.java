package com.soyoung.stocks.storage;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.SupabaseStockRepository;
import com.soyoung.stocks.service.InstrumentService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class SupabaseRepositoryTest {
  SupabaseClient client;
  SupabaseStockRepository repository;
  Instrument instrument;

  @BeforeEach
  void setup() {
    client = mock(SupabaseClient.class);
    repository = new SupabaseStockRepository(client);
    instrument = InstrumentService.identity("index/us-IXIC");
    instrument.id = 7;
    when(client.select(eq("instruments"), anyMap()))
        .thenReturn(
            Collections.singletonList(
                map(
                    "id",
                    7,
                    "market",
                    "us",
                    "exchange_name",
                    "US",
                    "code",
                    "IXIC",
                    "name",
                    "纳指",
                    "page_url",
                    instrument.pageUrl,
                    "currency",
                    "USD",
                    "enabled",
                    true,
                    "sort_order",
                    0,
                    "name_source",
                    "auto",
                    "created_at",
                    1,
                    "updated_at",
                    1)));
  }

  private Series series(long fetched, String source, double close) {
    Series s = new Series();
    s.source = source;
    s.fetchedAtMs = fetched;
    s.bars =
        Collections.singletonList(new Bar(1792497600L, close, close + 1, close - 1, close, 100.0));
    return s;
  }

  private Map<String, Object> row(Series s) {
    return map(
        "instrument_id",
        7,
        "period",
        s.period,
        "source",
        s.source,
        "adjustment",
        s.adjustment,
        "fetched_at",
        s.fetchedAtMs,
        "previous_close",
        s.previousClose,
        "note",
        s.note,
        "bars",
        MAPPER.convertValue(s.bars, List.class),
        "revision",
        "previous-revision");
  }

  @Test
  void rejectsInvalidCandlesBeforeAnyWriteAndSkipsStaleRevision() {
    Series invalid = series(2, "source", 100);
    invalid.bars.get(0).high = 1;
    assertThrows(IllegalArgumentException.class, () -> repository.saveSeries(instrument, invalid));
    when(client.select(eq("series"), anyMap()))
        .thenReturn(Collections.singletonList(row(series(10, "source", 100))));
    repository.saveSeries(instrument, series(2, "source", 100));
    verify(client, never()).insert(eq("series"), anyMap());
    verify(client, never()).request(eq("PATCH"), eq("series"), anyMap(), any(), anyString());
  }

  @Test
  void replacesProviderRevisionInOneConditionalRequest() {
    when(client.select(eq("series"), anyMap()))
        .thenReturn(Collections.singletonList(row(series(1, "provider A", 100))));
    when(client.request(eq("PATCH"), eq("series"), anyMap(), any(), anyString()))
        .thenReturn(Collections.singletonList(map("instrument_id", 7)));
    repository.saveSeries(instrument, series(2, "provider B", 110));
    ArgumentCaptor<Map> filter = ArgumentCaptor.forClass(Map.class),
        body = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .request(
            eq("PATCH"),
            eq("series"),
            filter.capture(),
            body.capture(),
            eq("return=representation"));
    assertEquals("eq.previous-revision", filter.getValue().get("revision"));
    assertEquals("provider B", body.getValue().get("source"));
    assertEquals(1, ((List<?>) body.getValue().get("bars")).size());
    verify(client, never()).delete(eq("series"), anyMap());
  }

  @Test
  void refusesToOverwriteAfterConcurrentChangesExhaustRetries() {
    when(client.select(eq("series"), anyMap()))
        .thenReturn(Collections.singletonList(row(series(1, "source", 100))));
    when(client.request(eq("PATCH"), eq("series"), anyMap(), any(), anyString()))
        .thenReturn(Collections.emptyList());
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> repository.saveSeries(instrument, series(2, "source", 110)));
    assertTrue(error.getMessage().contains("并发"));
    verify(client, times(4)).request(eq("PATCH"), eq("series"), anyMap(), any(), anyString());
    verify(client, never()).delete(eq("series"), anyMap());
  }

  @Test
  void doesNotTrustAnInstrumentOwnedByAnotherUser() {
    when(client.select(eq("instruments"), anyMap())).thenReturn(Collections.emptyList());
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveSeries(instrument, series(2, "source", 100)));
    verify(client, never()).insert(anyString(), anyMap());
  }
}
