package com.petertroll.quiethome

import java.io.ByteArrayOutputStream
import java.io.InputStream

object BoundedNetworkReader {
    fun readUtf8(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 16_384))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Network response was too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
