package com.tarek.guardian.vpn

/** Just enough DNS parsing to read the question and refuse it. */
object DnsPacket {

    /** The first question's name, e.g. "www.example.com", or null if unreadable. */
    fun questionName(msg: ByteArray): String? {
        if (msg.size < 12) return null
        val qd = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        if (qd < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (i < msg.size) {
            val len = msg[i].toInt() and 0xFF
            if (len == 0) return sb.toString()
            if (len and 0xC0 != 0) return null          // no compression in questions
            if (i + 1 + len > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until len) sb.append((msg[i + 1 + k].toInt() and 0xFF).toChar())
            i += 1 + len
        }
        return null
    }

    /** The query turned into an NXDOMAIN answer ("no such site"). */
    fun nxdomain(query: ByteArray): ByteArray {
        val r = query.copyOf()
        val rd = query[2].toInt() and 0x01
        r[2] = (0x80 or rd).toByte()      // QR=1, opcode 0, RD copied
        r[3] = 0x83.toByte()              // RA=1, RCODE=3 (NXDOMAIN)
        // No answer/authority/additional records.
        for (k in 6..11) r[k] = 0
        // Trim anything after the question (e.g. an EDNS record we no longer count).
        var i = 12
        while (i < r.size && r[i].toInt() != 0) i += 1 + (r[i].toInt() and 0xFF)
        val end = (i + 1 + 4).coerceAtMost(r.size)
        return r.copyOf(end)
    }
}

/** Builds the IPv4/UDP packet that carries a DNS answer back into the tunnel. */
object Ipv4Udp {

    /**
     * [request] is the captured query packet (IPv4 header length [ihl]); the
     * reply swaps addresses and ports and carries [payload].
     */
    fun reply(request: ByteArray, ihl: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[6] = 0x40                                  // don't fragment
        out[8] = 64                                    // TTL
        out[9] = 17                                    // UDP
        System.arraycopy(request, 16, out, 12, 4)      // src = their destination
        System.arraycopy(request, 12, out, 16, 4)      // dst = their source
        val cs = checksum(out, 0, 20)
        out[10] = (cs shr 8).toByte(); out[11] = cs.toByte()

        // UDP: ports swapped, checksum 0 (optional for IPv4).
        out[20] = request[ihl + 2]; out[21] = request[ihl + 3]
        out[22] = request[ihl]; out[23] = request[ihl + 1]
        val ulen = 8 + payload.size
        out[24] = (ulen shr 8).toByte(); out[25] = ulen.toByte()
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
