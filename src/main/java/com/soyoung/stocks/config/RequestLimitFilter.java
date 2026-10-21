package com.soyoung.stocks.config;

import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON input for both fixed-length and chunked requests. */
@Component
public class RequestLimitFilter extends OncePerRequestFilter {
  private static final long MAX = 16 * 1024 * 1024;

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getServletPath().startsWith("/api/")
        || request.getContentType() == null
        || !request.getContentType().startsWith("application/json")) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > MAX) {
      response.sendError(413, "请求不能超过16MB");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          private ServletInputStream stream;

          @Override
          public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
              ServletInputStream original = super.getInputStream();
              stream =
                  new ServletInputStream() {
                    long read;

                    private void count(int n) throws IOException {
                      if (n > 0 && (read += n) > MAX)
                        throw new IOException("JSON request exceeded 16MB");
                    }

                    public int read() throws IOException {
                      int value = original.read();
                      if (value >= 0) count(1);
                      return value;
                    }

                    public int read(byte[] b, int off, int len) throws IOException {
                      int n = original.read(b, off, len);
                      count(n);
                      return n;
                    }

                    public boolean isFinished() {
                      return original.isFinished();
                    }

                    public boolean isReady() {
                      return original.isReady();
                    }

                    public void setReadListener(ReadListener l) {
                      original.setReadListener(l);
                    }
                  };
            }
            return stream;
          }
        },
        response);
  }
}
