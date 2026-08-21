package com.teacup.teacuppicturebackend.auth;

import com.teacup.teacuppicturebackend.constant.UserConstant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Enumeration;
import java.util.regex.Pattern;

/**
 * Selects the login state for one browser tab while keeping authentication in
 * the server-owned HttpSession and HttpOnly Cookie.
 */
public final class SessionContext {
    public static final String HEADER = "X-Teacup-Session-Context";
    public static final String QUERY_PARAMETER = "sessionContext";
    private static final String DEFAULT_CONTEXT = "default";
    private static final String ATTRIBUTE_PREFIX = UserConstant.USER_LOGIN_STATE + "::";
    private static final Pattern VALID_CONTEXT = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private SessionContext() {
    }

    public static String contextId(HttpServletRequest request) {
        if (request == null) return DEFAULT_CONTEXT;
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) value = request.getParameter(QUERY_PARAMETER);
        if (value == null || value.isBlank()) return DEFAULT_CONTEXT;
        value = value.trim();
        return VALID_CONTEXT.matcher(value).matches() ? value : DEFAULT_CONTEXT;
    }

    public static boolean hasExplicitContext(HttpServletRequest request) {
        if (request == null) return false;
        String header = request.getHeader(HEADER);
        String query = request.getParameter(QUERY_PARAMETER);
        return (header != null && !header.isBlank()) || (query != null && !query.isBlank());
    }

    public static String loginAttribute(HttpServletRequest request) {
        return loginAttribute(contextId(request));
    }

    public static String loginAttribute(String contextId) {
        return ATTRIBUTE_PREFIX + contextId;
    }

    public static Object getLoginState(HttpSession session, HttpServletRequest request) {
        if (session == null) return null;
        Object value = session.getAttribute(loginAttribute(request));
        if (value == null && !hasExplicitContext(request)) {
            value = session.getAttribute(UserConstant.USER_LOGIN_STATE);
        }
        return value;
    }

    public static void setLoginState(HttpSession session, HttpServletRequest request, Object user) {
        if (hasExplicitContext(request)) {
            session.setAttribute(loginAttribute(request), user);
        } else {
            session.setAttribute(UserConstant.USER_LOGIN_STATE, user);
        }
    }

    public static void removeLoginState(HttpSession session, HttpServletRequest request) {
        if (session == null) return;
        session.removeAttribute(loginAttribute(request));
        if (!hasExplicitContext(request)) {
            session.removeAttribute(UserConstant.USER_LOGIN_STATE);
        }
    }

    public static boolean hasAnyLoginState(HttpSession session) {
        if (session == null) return false;
        Enumeration<String> names = session.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (UserConstant.USER_LOGIN_STATE.equals(name) || name.startsWith(ATTRIBUTE_PREFIX)) return true;
        }
        return false;
    }
}
