package com.soyoung.stocks.config;

import com.soyoung.stocks.storage.*;
import java.io.IOException;
import java.util.Collections;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@Profile("supabase")
public class SupabaseSecurityConfig extends WebSecurityConfigurerAdapter {
  private final SupabaseClient client;

  public SupabaseSecurityConfig(SupabaseClient client) {
    this.client = client;
  }

  protected void configure(HttpSecurity http) throws Exception {
    http.csrf()
        .disable()
        .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()
        .authorizeRequests()
        .antMatchers(
            "/",
            "/index.html",
            "/login",
            "/assets/**",
            "/*.js",
            "/*.css",
            "/api/storage",
            "/actuator/health",
            "/error")
        .permitAll()
        .anyRequest()
        .authenticated()
        .and()
        .exceptionHandling()
        .authenticationEntryPoint(
            (req, res, e) -> {
              res.setStatus(401);
              res.setContentType("application/json;charset=UTF-8");
              res.getWriter().write("{\"message\":\"请登录数据空间\"}");
            });
    // Only bearer tokens authenticate requests in this mode; no ambient cookie credentials.
    http.addFilterBefore(
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
              chain.doFilter(req, res);
              return;
            }
            UserScope.Identity identity;
            try {
              identity = client.authenticate(auth.substring(7));
            } catch (IllegalArgumentException e) {
              res.setStatus(401);
              res.setContentType("application/json;charset=UTF-8");
              res.getWriter().write("{\"message\":\"数据空间登录无效，请重新登录\"}");
              return;
            }
            SecurityContextHolder.getContext()
                .setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        identity.userId, null, Collections.emptyList()));
            try {
              UserScope.run(
                  identity,
                  () -> {
                    try {
                      chain.doFilter(req, res);
                    } catch (IOException | ServletException e) {
                      throw new FilterFailure(e);
                    }
                  });
            } catch (FilterFailure e) {
              if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
              throw (ServletException) e.getCause();
            } finally {
              SecurityContextHolder.clearContext();
            }
          }
        },
        UsernamePasswordAuthenticationFilter.class);
  }

  private static final class FilterFailure extends RuntimeException {
    FilterFailure(Exception e) {
      super(e);
    }
  }
}
