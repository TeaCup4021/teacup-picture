package com.teacup.teacuppicturebackend.auth;

import com.teacup.teacuppicturebackend.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContextTest {
    @Test
    void keepsLoginStatesIndependentWithinOneHttpSession() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest adminRequest = request("tab-admin");
        MockHttpServletRequest userRequest = request("tab-user");
        User admin = new User();
        User user = new User();

        SessionContext.setLoginState(session, adminRequest, admin);
        SessionContext.setLoginState(session, userRequest, user);

        assertSame(admin, SessionContext.getLoginState(session, adminRequest));
        assertSame(user, SessionContext.getLoginState(session, userRequest));
        assertTrue(SessionContext.hasAnyLoginState(session));
    }

    @Test
    void usesQueryContextForNativeAssetRequests() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(SessionContext.QUERY_PARAMETER, "tab-image");
        User user = new User();
        SessionContext.setLoginState(session, request, user);

        assertEquals("tab-image", SessionContext.contextId(request));
        assertSame(user, SessionContext.getLoginState(session, request));
    }

    @Test
    void removesOnlyTheCurrentContext() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest adminRequest = request("tab-admin");
        MockHttpServletRequest userRequest = request("tab-user");
        SessionContext.setLoginState(session, adminRequest, new User());
        SessionContext.setLoginState(session, userRequest, new User());

        SessionContext.removeLoginState(session, adminRequest);

        assertNull(SessionContext.getLoginState(session, adminRequest));
        assertTrue(SessionContext.getLoginState(session, userRequest) != null);
    }

    private static MockHttpServletRequest request(String context) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SessionContext.HEADER, context);
        return request;
    }
}
