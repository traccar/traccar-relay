package org.traccar.relay.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthUrlPolicyTest {

    @Test
    fun allowsOnlyExpectedGoogleHttpsOrigins() {
        assertTrue(GoogleAuthUrlPolicy.isAllowed("https://accounts.google.com/EmbeddedSetup"))
        assertTrue(GoogleAuthUrlPolicy.isAllowed("https://myaccount.google.com/security"))
        assertTrue(GoogleAuthUrlPolicy.isAllowed("https://accounts.google.com:443/"))

        assertFalse(GoogleAuthUrlPolicy.isAllowed("http://accounts.google.com/"))
        assertFalse(GoogleAuthUrlPolicy.isAllowed("https://evil.accounts.google.com/"))
        assertFalse(GoogleAuthUrlPolicy.isAllowed("https://accounts.google.com.evil.example/"))
        assertFalse(GoogleAuthUrlPolicy.isAllowed("https://user@accounts.google.com/"))
        assertFalse(GoogleAuthUrlPolicy.isAllowed("https://accounts.google.com:8443/"))
        assertFalse(GoogleAuthUrlPolicy.isAllowed("file:///tmp/login.html"))
        assertFalse(GoogleAuthUrlPolicy.isAllowed("https://accounts.google.com/%"))
    }

    @Test
    fun extractsOAuthTokenWithAnyCookieSpacing() {
        assertTrue(
            GoogleAuthCookieParser.findOAuthToken("foo=bar;oauth_token=oauth2_4/example; SID=value") ==
                "oauth2_4/example",
        )
        assertTrue(
            GoogleAuthCookieParser.findOAuthToken("foo=bar; oauth_token=oauth2_4/example") ==
                "oauth2_4/example",
        )
        assertTrue(GoogleAuthCookieParser.findOAuthToken("foo=bar") == null)
    }
}
