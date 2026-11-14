import com.tvnetsolutions.jr.M3uParser
import com.tvnetsolutions.jr.StreamKind

fun main() {
    val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="tv-1" tvg-logo="logos/uno.png" group-title="Nacionales", Canal Uno
        live/uno.m3u8
        #EXTINF:-1 type="mp4" tvg-name="Grabado", Canal Dos
        https://media.example.org/reel?id=123
        #EXTINF:-1, Canal Tres
        udp://239.11.1.2:5000
        #EXTINF:-1, Canal Cuatro
        rtp://239.11.1.2:5002
    """.trimIndent()
    val channels = M3uParser.parse(playlist, "https://media.example.org/lista/main.m3u")
    check(channels.size == 4) { "Esperados 4, recibidos ${channels.size}" }
    check(channels[0].name == "Canal Uno")
    check(channels[0].url == "https://media.example.org/lista/live/uno.m3u8")
    check(channels[0].logo == "https://media.example.org/lista/logos/uno.png")
    check(channels[1].kind == StreamKind.MP4)
    check(channels[2].kind == StreamKind.UDP_TS)
    check(channels[3].kind == StreamKind.RTP_UNSUPPORTED)
    check(M3uParser.parse("<html>Not an M3U</html>").isEmpty())
    val longPlaylist = buildString { append("#EXTM3U\n"); repeat(75) { append("#EXTINF:-1, Canal $it\nhttps://example.org/$it.m3u8\n") } }
    check(M3uParser.parse(longPlaylist).size == 75)
    val bigPlaylist = buildString { append("#EXTM3U\n"); repeat(120) { append("#EXTINF:-1, Canal $it\nhttp://10.0.0.1:8000/c$it\n") } }
    val big = M3uParser.parse(bigPlaylist)
    check(big.size == M3uParser.MAX_CHANNELS)
    check(big[0].kind == StreamKind.AUTO)
    check(M3uParser.parse("#EXTM3U\n#EXTINF:-1, A\nhttp://x/a.ts\n")[0].kind == StreamKind.HTTP_TS)
    println("OK: ParserTest – canales, logos, URL relativa, MP4, UDP, RTP, TS/AUTO y límite de 99")
}
