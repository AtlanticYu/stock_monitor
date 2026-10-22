package com.soyoung.stocks.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@Profile("!supabase")
public class SecurityConfig extends WebSecurityConfigurerAdapter {
  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http.authorizeRequests()
        .antMatchers("/api/storage", "/actuator/health", "/error", "/assets/app-icon.png")
        .permitAll()
        .anyRequest()
        .authenticated()
        .and()
        .formLogin()
        .loginPage("/login")
        .defaultSuccessUrl("/", true)
        .permitAll()
        .and()
        .httpBasic()
        .and()
        .csrf()
        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
    http.exceptionHandling()
        .defaultAuthenticationEntryPointFor(
            (req, res, ex) -> res.sendError(401, "Authentication required"),
            req -> req.getServletPath().startsWith("/api/"));
  }
}
