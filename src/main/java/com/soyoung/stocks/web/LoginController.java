package com.soyoung.stocks.web;

import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@Controller
@Profile("!supabase")
public class LoginController {
  @GetMapping("/login")
  @ResponseBody
  public ResponseEntity<String> login(HttpServletRequest request) throws Exception {
    String page;
    try (java.io.InputStream input = getClass().getResourceAsStream("/login.html")) {
      page = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
    }
    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    page =
        page.replace("{{context}}", HtmlUtils.htmlEscape(request.getContextPath()))
            .replace("{{csrf}}", HtmlUtils.htmlEscape(csrf.getToken()))
            .replace(
                "{{message}}",
                request.getParameter("error") != null
                    ? "用户名或密码不正确，请重试。"
                    : request.getParameter("logout") != null ? "你已安全退出登录。" : "登录后查看行情、管理数据与运行研究。");
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
        .body(page);
  }
}
