package moe.ouom.neriplayer.lyrics.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BilingualLyricsTest {
    @Test
    fun `interleaved translation lines are split into the translation channel`() {
        val source = lyricLines(
            "00:01.00" to "Hello world",
            "00:01.00" to "你好世界",
            "00:05.00" to "Second line",
            "00:05.00" to "第二行",
            "00:09.00" to "Third line",
            "00:09.00" to "第三句"
        )

        val split = splitBilingualLyrics(source, null)

        assertEquals(
            lyricLines(
                "00:01.00" to "Hello world",
                "00:05.00" to "Second line",
                "00:09.00" to "Third line"
            ),
            split.original
        )
        assertEquals(
            lyricLines(
                "00:01.00" to "你好世界",
                "00:05.00" to "第二行",
                "00:09.00" to "第三句"
            ),
            split.translated
        )
    }

    @Test
    fun `translations written as a trailing block are still paired by timestamp`() {
        val source = lyricLines(
            "00:01.00" to "Hello",
            "00:05.00" to "Second",
            "00:09.00" to "Third",
            "00:01.00" to "你好",
            "00:05.00" to "第二",
            "00:09.00" to "第三"
        )

        val split = splitBilingualLyrics(source, null)

        assertNotNull(split.translated)
        assertEquals(3, split.original?.lines()?.size)
        assertEquals(3, split.translated?.lines()?.size)
    }

    @Test
    fun `occasional duplicate timestamps in a normal lyric are not treated as translations`() {
        val lines = (1..20).map { index -> "00:%02d.00".format(index) to "Line $index" }
            .toMutableList()
        lines[2] = "00:02.00" to "Line 2"
        lines[3] = "00:02.00" to "Line 2 repeat"
        lines[10] = "00:10.00" to "Line 10"
        lines[11] = "00:10.00" to "Line 10 echo"

        val split = splitBilingualLyrics(lyricLines(*lines.toTypedArray()), null)

        assertNull(split.translated)
        assertEquals(lyricLines(*lines.toTypedArray()), split.original)
    }

    @Test
    fun `credit lines stay in the original lyric`() {
        val source = lyricLines(
            "00:00.00" to "作词: A",
            "00:00.00" to "作曲: B",
            "00:01.00" to "Hello",
            "00:01.00" to "你好",
            "00:05.00" to "Second",
            "00:05.00" to "第二"
        )

        val split = splitBilingualLyrics(source, null)

        assertNotNull(split.translated)
        assertEquals(4, split.original?.lines()?.size)
        assertEquals(2, split.translated?.lines()?.size)
    }

    @Test
    fun `plain text without timestamps is left untouched`() {
        val source = "just some text\nno timestamps here"

        val split = splitBilingualLyrics(source, null)

        assertNull(split.translated)
        assertEquals(source, split.original)
    }

    @Test
    fun `existing translation channel is never overwritten`() {
        val source = lyricLines(
            "00:01.00" to "Hello",
            "00:01.00" to "你好",
            "00:05.00" to "Second",
            "00:05.00" to "第二"
        )

        val split = splitBilingualLyrics(source, "already translated")

        assertEquals(source, split.original)
        assertEquals("already translated", split.translated)
    }

    @Test
    fun `single pairing and sparse translations are rejected`() {
        val singlePair = lyricLines(
            "00:01.00" to "Hello",
            "00:01.00" to "你好",
            "00:05.00" to "Second"
        )
        assertNull(splitBilingualLyrics(singlePair, null).translated)

        val sparse = buildString {
            for (index in 1..20) {
                if (isNotEmpty()) append('\n')
                append("[00:%02d.00]Line %d".format(index, index))
                if (index == 2 || index == 8) {
                    append('\n')
                    append("[00:%02d.00]译文 %d".format(index, index))
                }
            }
        }
        assertNull(splitBilingualLyrics(sparse, null).translated)
    }

    @Test
    fun `small timestamp drift between the pair is tolerated`() {
        val source = lyricLines(
            "00:01.00" to "Hello",
            "00:01.02" to "你好",
            "00:05.00" to "Second",
            "00:05.02" to "第二"
        )

        val split = splitBilingualLyrics(source, null)

        assertNotNull(split.translated)
        assertEquals(2, split.translated?.lines()?.size)
    }

    private fun lyricLines(vararg lines: Pair<String, String>): String =
        lines.joinToString(separator = "\n") { (timestamp, text) -> "[$timestamp]$text" }
}
