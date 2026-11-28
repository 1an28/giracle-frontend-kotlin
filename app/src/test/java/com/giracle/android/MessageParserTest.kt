package com.giracle.android

import com.giracle.android.data.MessageParser
import com.giracle.android.data.Segment
import com.giracle.android.data.net.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageParserTest {
    private val uid = "071252d8-742a-4b42-b112-89bc2631cdc3"
    private val cid = "c56a341a-f3e2-4f72-a23a-3ae301cf448a"

    @Test fun plainText() {
        assertEquals(listOf(Segment.Text("こんにちは")), MessageParser.parse("こんにちは"))
    }

    @Test fun allKinds() {
        val s = MessageParser.parse("hi @<$uid> see https://example.com/a?b=1 in #<$cid> and &<$cid:$uid> `code`")
        assertEquals(
            listOf(
                Segment.Text("hi "), Segment.Mention(uid), Segment.Text(" see "), Segment.Link("https://example.com/a?b=1"),
                Segment.Text(" in "), Segment.ChannelRef(cid), Segment.Text(" and "), Segment.MessageLink(cid, uid),
                Segment.Text(" "), Segment.Code("code"),
            ),
            s,
        )
    }

    @Test fun longText() {
        assert(MessageParser.isLong("a\nb\nc\nd\ne\nf\ng"))
        assert(!MessageParser.isLong("short"))
        assert(MessageParser.isLong("x".repeat(501)))
    }

    @Test fun endpointProduction() {
        val e = Endpoint.parse("chat.example.com")!!
        assertEquals("https://chat.example.com/api/", e.apiBase.toString())
        assertEquals("wss://chat.example.com/ws", e.wsUrl)
        assertEquals("https://chat.example.com", e.originHeader)
    }

    @Test fun endpointFlutterStyleAndPort() {
        val e = Endpoint.parse("http://192.168.0.5:3333/api/")!!
        assertEquals("http://192.168.0.5:3333/api/", e.apiBase.toString())
        assertEquals("ws://192.168.0.5:3333/ws", e.wsUrl)
        assertEquals("http://192.168.0.5:3333", e.originHeader)
        val d = Endpoint.parse("http://localhost:3000", direct = true)!!
        assertEquals("http://localhost:3000/", d.apiBase.toString())
    }
}
