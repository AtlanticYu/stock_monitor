package com.soyoung.stocks;

import static org.junit.jupiter.api.Assertions.*;

import com.soyoung.stocks.domain.Models.Instrument;
import com.soyoung.stocks.service.InstrumentService;
import org.junit.jupiter.api.Test;

class InstrumentIdentityTest {
  @Test
  void recognizesIndexUrlsAndExplicitShortPathsWithoutTurningThemIntoStocks() {
    for (String input :
        new String[] {
          "https://finance.baidu.com/index/us-IXIC", "index/us-ixic", "/index/US-IXIC"
        }) {
      Instrument index = InstrumentService.identity(input);
      assertEquals("us", index.market);
      assertEquals("IXIC", index.code);
      assertEquals("USD", index.currency);
      assertEquals("纳斯达克综合指数", index.name);
      assertEquals("https://finance.baidu.com/index/us-IXIC", index.pageUrl);
      assertEquals("index", InstrumentService.financeType(index));
      assertEquals(
          "index", InstrumentService.financeType(InstrumentService.identity(index.pageUrl)));
    }
  }

  @Test
  void acceptsHongKongAndMainlandIndicesButKeepsStockCodeValidation() {
    Instrument hsi = InstrumentService.identity("index/hk-HSI");
    assertEquals("HKD", hsi.currency);
    assertEquals("HSI", hsi.code);
    assertEquals("index", InstrumentService.financeType(hsi));
    Instrument sh = InstrumentService.identity("index/sh-000001");
    assertEquals("cn-sh", sh.market);
    assertEquals("000001", sh.code);
    assertEquals("CNY", sh.currency);
    assertEquals("cn-sz", InstrumentService.identity("index/sz-399001").market);
    assertThrows(IllegalArgumentException.class, () -> InstrumentService.identity("hk-HSI"));
    assertThrows(IllegalArgumentException.class, () -> InstrumentService.identity("index/sh-HSI"));
  }

  @Test
  void preservesExistingStockUrlsAndMarketCodes() {
    for (String input : new String[] {"us-AAPL", "https://finance.baidu.com/stock/us-aapl"}) {
      Instrument stock = InstrumentService.identity(input);
      assertEquals("https://finance.baidu.com/stock/us-aapl", stock.pageUrl);
      assertEquals("stock", InstrumentService.financeType(stock));
    }
    assertEquals("00700", InstrumentService.identity("hk-00700").code);
    assertEquals("cn-sh", InstrumentService.identity("sh-600519").market);
    assertEquals("cn-sz", InstrumentService.identity("sz-000001").market);
    assertEquals("cn-ab", InstrumentService.identity("ab-688825").market);
  }

  @Test
  void indexUrlsRetainTheSameHostAndPathRestrictions() {
    for (String input :
        new String[] {
          "http://finance.baidu.com/index/us-IXIC",
          "https://evil.example/index/us-IXIC",
          "https://finance.baidu.com:8443/index/us-IXIC",
          "https://user@finance.baidu.com/index/us-IXIC",
          "https://finance.baidu.com/index/us-IXIC?url=http://127.0.0.1",
          "https://finance.baidu.com/index/us-IXIC#fragment",
          "https://finance.baidu.com/us-IXIC",
          "https://finance.baidu.com/other/us-IXIC",
          "https://finance.baidu.com/index/us-IXIC/extra",
          "index/../stock/us-AAPL"
        }) {
      assertThrows(IllegalArgumentException.class, () -> InstrumentService.identity(input), input);
    }
  }
}
