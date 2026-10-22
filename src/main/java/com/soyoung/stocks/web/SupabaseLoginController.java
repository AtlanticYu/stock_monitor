package com.soyoung.stocks.web;

import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

@Controller
@Profile("supabase")
public class SupabaseLoginController {
  @GetMapping("/login")
  public ResponseEntity<String> login(HttpServletRequest request) throws java.io.IOException {
    String page =
        StreamUtils.copyToString(
            getClass().getResourceAsStream("/supabase-login.html"), StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
        .body(page.replace("{{context}}", HtmlUtils.htmlEscape(request.getContextPath())));
  }
}
