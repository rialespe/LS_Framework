package com.lssoluciones.lscore.session;

/** Fixed HTTP selectors, resolved to catalog UUIDs exclusively by trusted server configuration. */
public enum LoginApplication {
    FISCAL("fiscal", "__Secure-LS-FISCAL-SESSION", "__Host-LS-FISCAL-CSRF"),
    POS("pos", "__Secure-LS-POS-SESSION", "__Host-LS-POS-CSRF"),
    CORE("core", "__Secure-LS-CORE-SESSION", "__Host-LS-CORE-CSRF");

    private final String slug;
    private final String sessionCookie;
    private final String csrfCookie;

    LoginApplication(String slug, String sessionCookie, String csrfCookie) {
        this.slug = slug;
        this.sessionCookie = sessionCookie;
        this.csrfCookie = csrfCookie;
    }

    public String path() {
        return "/api/auth/" + slug;
    }

    public String sessionCookie() {
        return sessionCookie;
    }

    public String csrfCookie() {
        return csrfCookie;
    }
}
