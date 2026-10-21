package com.soyoung.stocks;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.soyoung.stocks.domain.Models.Bar;
import com.soyoung.stocks.service.IndicatorEngine;
import java.util.*;
import org.junit.jupiter.api.Test;

class IndicatorParityTest {
  @Test
  void matchesRustIndicatorFixture() throws Exception {
    JsonNode fixture =
        MAPPER.readTree(getClass().getResourceAsStream("/fixtures/rust-indicators.json"));
    List<Bar> bars = Arrays.asList(MAPPER.treeToValue(fixture.get("bars"), Bar[].class));
    List<IndicatorEngine.Result> actual =
        new IndicatorEngine()
            .calculate(bars, object(read(fixture.get("request").toString())), "us", "day");
    List<String> errors = new ArrayList<>();
    for (JsonNode expected : fixture.get("results")) {
      String name = expected.path("name").asText();
      IndicatorEngine.Result r = IndicatorEngine.find(actual, name);
      assertNotNull(r, name);
      compare(expected.get("values"), MAPPER.valueToTree(r.values), name, errors);
      JsonNode extra = expected.get("extra");
      if (extra != null && extra.isObject())
        extra
            .fields()
            .forEachRemaining(
                e -> {
                  if (e.getValue().isArray() && e.getValue().size() == bars.size())
                    compare(
                        e.getValue(),
                        MAPPER.valueToTree(r.extra == null ? null : r.extra.get(e.getKey())),
                        name + "." + e.getKey(),
                        errors);
                });
    }
    assertTrue(errors.isEmpty(), String.join("\n", errors));
  }

  private void compare(JsonNode expected, JsonNode actual, String name, List<String> errors) {
    if (actual == null || !actual.isArray()) {
      errors.add(name + " missing");
      return;
    }
    if (expected.size() != actual.size()) {
      errors.add(name + " length");
      return;
    }
    for (int i = 0; i < expected.size(); i++) {
      JsonNode a = actual.get(i), e = expected.get(i);
      boolean ok =
          e.isNumber()
              ? a.isNumber()
                  && Math.abs(a.asDouble() - e.asDouble())
                      < 1e-7 * Math.max(1, Math.abs(e.asDouble()))
              : e.equals(a);
      if (!ok) {
        errors.add(name + " at " + i + ": " + e + " != " + a);
        break;
      }
    }
  }
}
