package com.qaliye.backend.config;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.user.UserStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class UserStatusFilter extends OncePerRequestFilter {

    private static final String SUSPENDED_JSON =
            "{\"error\":\"account_suspended\",\"message\":\"Account suspended\",\"status\":403}";

    private final UserStatusService userStatusService;

    public UserStatusFilter(UserStatusService userStatusService) {
        this.userStatusService = userStatusService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        UUID callerId = CallerUtils.callerId();
        UserStatusService.UserStatus userStatus = userStatusService.getStatus(callerId);

        if (userStatus == null
                || "SUSPENDED".equals(userStatus.status())
                || "DEACTIVATED".equals(userStatus.status())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(SUSPENDED_JSON);
            return;
        }

        request.setAttribute("callerStatus", userStatus);
        filterChain.doFilter(request, response);
    }
}
