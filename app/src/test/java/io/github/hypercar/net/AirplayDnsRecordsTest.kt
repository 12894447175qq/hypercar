package io.github.hypercar.net

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class AirplayDnsRecordsTest {
    private fun name(value: String): ByteArray = ByteArrayOutputStream().apply {
        value.split('.').forEach { write(it.length); write(it.toByteArray()) }
        write(0)
    }.toByteArray()
    private fun packet(answers: Int, additional: Int, body: ByteArray, questions: Int = 0) =
        ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeShort(0); writeShort(0x8400); writeShort(questions)
                writeShort(answers); writeShort(0); writeShort(additional); write(body)
            }
        }.toByteArray()
    private fun record(owner: ByteArray, type: Int, data: ByteArray) = ByteArrayOutputStream().apply {
        DataOutputStream(this).apply {
            write(owner); writeShort(type); writeShort(0x8001); writeInt(120); writeShort(data.size); write(data)
        }
    }.toByteArray()
    private fun srv(owner: ByteArray, host: ByteArray) = record(owner, 33,
        byteArrayOf(0, 0, 0, 0, 0x1b, 0x58) + host) // 7000

    @Test fun compressedQuestionAndAdditionalSrvAndAddressResolve() {
        val service = name("_airplay._tcp.local")
        val question = service + byteArrayOf(0, 12, 0, 1)
        val instance = name("CarPlay._airplay._tcp.local")
        val ptr = record(byteArrayOf(0xc0.toByte(), 12), 12, instance)
        val host = name("mib3.local")
        val body = question + ptr + srv(instance, host) + record(host, 1, byteArrayOf(10, -82, -67, 1))
        val records = AirplayDnsRecords()
        records.accept(packet(1, 2, body, questions = 1), 12 + body.size)
        assertEquals("10.174.189.1", records.endpoint()!!.host)
        assertEquals(7000, records.endpoint()!!.port)
    }

    @Test fun partialRecordsAcrossPacketsAndCompressedHostResolve() {
        val instance = name("CarPlay._airplay._tcp.local")
        val host = name("mib3.local")
        val records = AirplayDnsRecords()
        val first = packet(1, 0, srv(instance, host))
        records.accept(first, first.size)
        assertNull(records.endpoint())
        // The A owner uses a compression pointer to the SRV target from the same packet.
        val targetOffset = 12 + instance.size + 10 + 6
        val pointer = byteArrayOf((0xc0 or (targetOffset ushr 8)).toByte(), targetOffset.toByte())
        val second = packet(1, 1, srv(instance, host) + record(pointer, 1, byteArrayOf(10, 1, 2, 1)))
        records.accept(second, second.size)
        assertEquals("10.1.2.1", records.endpoint()!!.host)
    }

    @Test fun unrelatedServiceAndMalformedPacketsDoNotCreateEndpoint() {
        val records = AirplayDnsRecords()
        val host = name("home.local")
        val unrelated = packet(1, 1, srv(name("TV._raop._tcp.local"), host) + record(host, 1, byteArrayOf(1, 2, 3, 4)))
        records.accept(unrelated, unrelated.size)
        assertNull(records.endpoint())
        val cycle = packet(1, 0, byteArrayOf(0xc0.toByte(), 12))
        records.accept(cycle, cycle.size)
        for (size in 0..unrelated.size) records.accept(unrelated, size)
        assertNull(records.endpoint())
    }
}
