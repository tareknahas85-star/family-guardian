package com.tarek.guardian.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsPacketsTest {

    /** A real A-record query for www.example.com with an EDNS record. */
    private fun query(): ByteArray {
        val h = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1)
        val q = byteArrayOf(3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() +
            byteArrayOf(0, 0, 1, 0, 1)
        val edns = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)
        return h + q + edns
    }

    @Test fun readsQuestionName() {
        assertEquals("www.example.com", DnsPacket.questionName(query()))
    }

    @Test fun nxdomainKeepsIdAndQuestionDropsExtras() {
        val q = query()
        val r = DnsPacket.nxdomain(q)
        assertEquals(0x12, r[0].toInt() and 0xFF)
        assertEquals(0x34, r[1].toInt() and 0xFF)
        assertEquals(0x81, r[2].toInt() and 0xFF)          // QR + RD
        assertEquals(3, r[3].toInt() and 0x0F)              // NXDOMAIN
        assertEquals(1, r[5].toInt())                       // one question
        assertEquals(0, r[11].toInt())                      // no additional records
        assertEquals(12 + 17 + 4, r.size)                   // header + name + type/class
        assertEquals("www.example.com", DnsPacket.questionName(r))
    }

    @Test fun replySwapsAddressesAndPortsWithValidChecksum() {
        // IPv4 10.111.0.2:40000 -> 10.111.0.53:53, UDP
        val payload = query()
        val req = ByteArray(28 + payload.size)
        req[0] = 0x45; req[9] = 17
        byteArrayOf(10, 111, 0, 2).copyInto(req, 12)
        byteArrayOf(10, 111, 0, 53).copyInto(req, 16)
        req[20] = (40000 shr 8).toByte(); req[21] = 40000.toByte()
        req[22] = 0; req[23] = 53
        payload.copyInto(req, 28)

        val answer = byteArrayOf(1, 2, 3, 4)
        val out = Ipv4Udp.reply(req, 20, answer)
        assertEquals(32, out.size)
        assertArrayEquals(byteArrayOf(10, 111, 0, 53), out.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 111, 0, 2), out.copyOfRange(16, 20))
        assertEquals(53, ((out[20].toInt() and 0xFF) shl 8) or (out[21].toInt() and 0xFF))
        assertEquals(40000, ((out[22].toInt() and 0xFF) shl 8) or (out[23].toInt() and 0xFF))
        assertEquals(12, ((out[24].toInt() and 0xFF) shl 8) or (out[25].toInt() and 0xFF))
        // Header checksum: summing the whole header must give 0xFFFF.
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
        assertArrayEquals(answer, out.copyOfRange(28, 32))
    }
}
