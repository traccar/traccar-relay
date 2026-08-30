package org.traccar.relay.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlValidatorTest {

    @Test
    fun acceptsHttpAndHttpsTraccarServers() {
        assertEquals("http://traccar.local:8082", ServerUrlValidator.normalize(" http://traccar.local:8082/ "))
        assertEquals("https://traccar.example.com", ServerUrlValidator.normalize("https://traccar.example.com/"))
    }

    @Test
    fun rejectsUnsafeOrAmbiguousServerUrls() {
        listOf(
            "ftp://traccar.example.com",
            "https://user:password@traccar.example.com",
            "https://traccar.example.com/?token=secret",
            "https://traccar.example.com/#fragment",
            "not-a-url",
            "https://traccar.example.com/%",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerUrlValidator.normalize(value)
            }
        }
    }
}
