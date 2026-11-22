package com.pagos.payments.adapter.web;

import com.pagos.payments.support.Correlation;
import com.pagos.payments.support.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Propaga o genera el correlation id y lo deja en el MDC para los logs estructurados. */
@Component
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(Correlation.HEADER);
        String id = incoming != null && incoming.matches("[A-Za-z0-9-]{8,64}") ? incoming : UuidV7.next();
        MDC.put(Correlation.MDC_KEY, id);
        response.setHeader(Correlation.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
