package moe.ouom.neriplayer.lyrics.embedded

import kotlin.math.abs
import moe.ouom.neriplayer.lyrics.parser.isLyricCreditMetadataLine

/**
 * 双语歌词的拆分结果；[translated] 为 null 表示没有识别出「同一时间戳下原文+译文」的结构。
 */
data class BilingualLyricsSplit(
    val original: String?,
    val translated: String?
)

private const val PAIR_TIMESTAMP_TOLERANCE_MS = 40L

/** 至少要有这么多组「同一时间戳两行」才认为整段是双语的 */
private const val MIN_BILINGUAL_PAIRS = 2

/** 参与配对的歌词行占全部带时间戳歌词行的最低比例，低于它就当作普通歌词 */
private const val MIN_PAIRED_LINE_RATIO = 0.8

private val lyricTimestampPrefixRegex = Regex("""^\s*((?:\[\p{Nd}{1,3}:\p{Nd}{2}(?:[.:]\p{Nd}{1,3})?])+)""")
private val lyricTimestampRegex = Regex("""\[(\p{Nd}{1,3}):(\p{Nd}{2})(?:[.:](\p{Nd}{1,3}))?]""")

/**
 * 把「译文紧跟原文、复用同一时间戳」的双语 LRC 拆成原文与译文两段。
 *
 * Lyrico、音乐标签等外部工具写回歌词时，常把译文直接追加在原文下一行并沿用同一时间戳；
 * 这种内容如果整段当作原文，播放页会把译文再当成一句原文重复显示。这里按时间戳成组判断：
 *
 * - 同一开始时间（允许 [PAIR_TIMESTAMP_TOLERANCE_MS] 的写入误差）下恰好两行、文本不同且都不是
 *   制作信息行，才视为「原文 + 译文」；
 * - 只有参与配对的行占全部带时间戳歌词行达到 [MIN_PAIRED_LINE_RATIO] 且不少于
 *   [MIN_BILINGUAL_PAIRS] 组时才拆分，避免把偶尔重复时间戳的普通歌词误判成双语；
 * - 没有第二个歌词字段时才会调用，已有独立译文的内容保持原样。
 *
 * 未参与配对的行（间奏、纯时间戳行、制作信息、无时间戳文本）原样留在原文里。
 */
fun splitBilingualLyrics(
    original: String?,
    translated: String?
): BilingualLyricsSplit {
    if (original.isNullOrBlank()) return BilingualLyricsSplit(original, translated)
    if (!translated.isNullOrBlank()) return BilingualLyricsSplit(original, translated)

    val lines = original.lines()
    val candidates = lines.mapIndexedNotNull { index, line ->
        parseTimedLyricLine(line)
            ?.takeIf { timed -> timed.text.isNotBlank() && !isLyricCreditMetadataLine(timed.text) }
            ?.copy(index = index)
    }
    if (candidates.size < MIN_BILINGUAL_PAIRS * 2) {
        return BilingualLyricsSplit(original, null)
    }

    val groups = groupByStartTime(candidates)
    val pairs = groups.filter { group -> group.size == 2 && isBilingualPair(group[0], group[1]) }
    val pairedLineCount = pairs.size * 2
    if (pairs.size < MIN_BILINGUAL_PAIRS ||
        pairedLineCount < candidates.size * MIN_PAIRED_LINE_RATIO
    ) {
        return BilingualLyricsSplit(original, null)
    }

    val translationIndices = pairs.mapTo(HashSet()) { pair -> pair[1].index }
    val originalBuilder = StringBuilder()
    val translatedBuilder = StringBuilder()
    lines.forEachIndexed { index, line ->
        if (index in translationIndices) {
            appendLyricLine(translatedBuilder, line)
        } else {
            appendLyricLine(originalBuilder, line)
        }
    }
    val translatedLyrics = translatedBuilder.toString()
    if (translatedLyrics.isBlank()) return BilingualLyricsSplit(original, null)
    return BilingualLyricsSplit(
        original = originalBuilder.toString(),
        translated = translatedLyrics
    )
}

private data class TimedLyricLine(
    val index: Int,
    val startTimeMs: Long,
    val text: String,
    val timestampCount: Int
)

private fun parseTimedLyricLine(raw: String): TimedLyricLine? {
    val match = lyricTimestampPrefixRegex.find(raw) ?: return null
    val prefix = match.groupValues[1]
    val text = raw.substring(match.range.last + 1).trim()
    val timestamps = lyricTimestampRegex.findAll(prefix).toList()
    val first = timestamps.firstOrNull() ?: return null
    val minutes = first.groupValues[1].toLongOrNull() ?: return null
    val seconds = first.groupValues[2].toLongOrNull() ?: return null
    val startTimeMs = minutes * 60_000L + seconds * 1_000L + parseFractionMs(first.groupValues[3])
    return TimedLyricLine(
        index = 0,
        startTimeMs = startTimeMs,
        text = text,
        timestampCount = timestamps.size
    )
}

private fun parseFractionMs(raw: String): Long {
    if (raw.isBlank()) return 0L
    return when (raw.length) {
        1 -> raw.toLongOrNull()?.times(100)
        2 -> raw.toLongOrNull()?.times(10)
        else -> raw.toLongOrNull()
    } ?: 0L
}

/** 把候选行按开始时间聚成组；容差内的写入误差合并到同一组，组内保持文件顺序 */
private fun groupByStartTime(candidates: List<TimedLyricLine>): List<List<TimedLyricLine>> {
    val groups = ArrayList<MutableList<TimedLyricLine>>()
    candidates.forEach { line ->
        val target = groups.firstOrNull { group ->
            abs(group.first().startTimeMs - line.startTimeMs) <= PAIR_TIMESTAMP_TOLERANCE_MS
        }
        if (target != null) target += line else groups += mutableListOf(line)
    }
    return groups
}

private fun isBilingualPair(original: TimedLyricLine, translated: TimedLyricLine): Boolean {
    return original.text != translated.text &&
        original.timestampCount == translated.timestampCount
}

private fun appendLyricLine(builder: StringBuilder, line: String) {
    if (builder.isNotEmpty()) builder.append('\n')
    builder.append(line)
}
