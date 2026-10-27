package io.github.hypercar.net

/** DNS-SD records may span packets and put SRV/TXT/A in the additional section. */
internal class AirplayDnsRecords {
    data class Endpoint(val host: String, val port: Int, val instance: String, val model: String?)
    private val services = HashMap<String, Pair<Int, String>>()
    private val addresses = HashMap<String, String>()
    private val models = HashMap<String, String>()

    fun endpoint(): Endpoint? = services.entries.firstNotNullOfOrNull { (instance, srv) ->
        addresses[srv.second]?.let { Endpoint(it, srv.first, instance, models[instance]) }
    }

    fun accept(data: ByteArray, length: Int) {
        if (length !in 12..data.size) return
        runCatching {
            fun u16(at: Int): Int {
                require(at >= 0 && at + 2 <= length)
                return ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
            }
            if (u16(2) and 0x8000 == 0) return // A query (possibly our own multicast) is not a service response.
            var pos = 12
            fun name(): String {
                var cursor = pos
                var end: Int? = null
                val labels = ArrayList<String>()
                val visited = HashSet<Int>()
                while (true) {
                    require(cursor in 0 until length && visited.add(cursor))
                    val size = data[cursor].toInt() and 255
                    when {
                        size == 0 -> { pos = end ?: (cursor + 1); break }
                        size and 0xc0 == 0xc0 -> {
                            require(cursor + 1 < length)
                            if (end == null) end = cursor + 2
                            cursor = ((size and 0x3f) shl 8) or (data[cursor + 1].toInt() and 255)
                        }
                        else -> {
                            require(size <= 63 && cursor + 1 + size <= length && labels.size < 128)
                            labels.add(String(data, cursor + 1, size, Charsets.UTF_8))
                            cursor += size + 1
                        }
                    }
                }
                return labels.joinToString(".").lowercase(java.util.Locale.ROOT)
            }
            repeat(u16(4).also { require(it <= 256) }) { name(); pos += 4; require(pos <= length) }
            val count = u16(6) + u16(8) + u16(10)
            require(count <= 256)
            repeat(count) {
                val owner = name()
                val type = u16(pos)
                val klass = u16(pos + 2) and 0x7fff
                require(pos + 10 <= length)
                val goodbye = data.sliceArray(pos + 4 until pos + 8).all { it == 0.toByte() }
                val size = u16(pos + 8)
                val start = pos + 10
                val end = start + size
                require(end <= length)
                if (klass == 1) when (type) {
                    33 -> if (owner.endsWith("._airplay._tcp.local") && size >= 7) {
                        val port = u16(start + 4)
                        pos = start + 6 // Skip priority, weight AND port before reading target.
                        val target = name()
                        require(pos <= end)
                        if (goodbye) services.remove(owner)
                        else if (port in 1..65535) services[owner] = port to target
                    }
                    1 -> if (size == 4) {
                        if (goodbye) addresses.remove(owner)
                        else addresses[owner] = (start until end).joinToString(".") { (data[it].toInt() and 255).toString() }
                    }
                    16 -> {
                        var i = start
                        while (i < end) {
                            val n = data[i].toInt() and 255
                            require(i + 1 + n <= end)
                            val txt = String(data, i + 1, n, Charsets.UTF_8)
                            if (txt.startsWith("model=")) models[owner] = txt.substringAfter('=')
                            i += n + 1
                        }
                    }
                }
                pos = end
            }
        }
    }
}
