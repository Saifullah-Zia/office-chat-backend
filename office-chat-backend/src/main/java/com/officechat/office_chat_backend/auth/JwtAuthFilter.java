package com.officechat.office_chat_backend.auth;

import com.officechat.office_chat_backend.user.User;
import com.officechat.office_chat_backend.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserService userService;

    /** Only skip JWT filtering for login endpoint when no token is needed */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // /api/auth/login does not require JWT filtering
        return "/api/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            System.out.println("JWT FILTER [NO BEARER]: URI=" + request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty() || "null".equalsIgnoreCase(token) || "undefined".equalsIgnoreCase(token)) {
            System.out.println("JWT FILTER [EMPTY TOKEN]: URI=" + request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String email = jwtService.extractEmail(token);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                User user = userService.loadUserByUsername(email);

                if (jwtService.isTokenValid(token, user)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    System.out.println("JWT FILTER [SUCCESS]: URI=" + request.getRequestURI() + " user=" + email);
                } else {
                    System.out.println("JWT FILTER [INVALID TOKEN]: URI=" + request.getRequestURI() + " user=" + email);
                }
            }
        } catch (Exception e) {
            System.out.println("JWT FILTER [EXCEPTION]: URI=" + request.getRequestURI() + " err=" + e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired JWT token. Please log in again.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}