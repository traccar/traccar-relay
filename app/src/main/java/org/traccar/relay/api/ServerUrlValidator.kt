package org.traccar.relay.api

import java.net.URI
import java.net.URISyntaxException

object ServerUrlValidator {

    fun normalize(value: String): String {
        val uri = try {
            URI(value.trim())
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Invalid server URL")
        }

        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
            "Server URL must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Server URL must contain a valid host"
        }
        require(uri.fragment == null && uri.query == null) {
            "Server URL cannot contain a query or fragment"
        }

        return uri.toASCIIString().trimEnd('/')
    }
}
