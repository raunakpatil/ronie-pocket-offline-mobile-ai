package com.raunakpatil.roniepocket.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Speaks Ronie's reply while it is still being written: streamed text -> sentences -> phonemes ->
 * Paradee audio -> AudioTrack. Synthesis runs up to two sentences ahead of playback, so speech starts
 * after the first sentence and doesn't gap. [stop] cuts everything immediately (barge-in).
 */
class Speaker(private val g2p: G2P, private val tts: Paradee, private val scope: CoroutineScope) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    var speed: Float = 1.0f
    private val pending = StringBuilder()
    private var sentences = Channel<String>(Channel.UNLIMITED)
    private var audio = Channel<FloatArray>(2)              // the "two sentences ahead" buffer
    private var jobs = emptyList<Job>()
    private var track: AudioTrack? = null

    /** Feed streamed reply text; complete sentences are queued for speech. */
    fun feed(chunk: String) {
        ensureRunning()
        if (firstFeedAt == 0L) firstFeedAt = System.currentTimeMillis()
        pending.append(chunk)
        while (true) {
            val cut = (if (!sentFirst) firstClauseEnd(pending) else null) ?: sentenceEnd(pending) ?: break
            sentFirst = true
            val s = pending.substring(0, cut).trim()
            pending.delete(0, cut)
            if (s.isNotBlank()) sentences.trySend(s)
        }
    }

    /** The reply is complete: speak whatever is left. */
    fun finish() {
        val s = pending.toString().trim()
        pending.setLength(0)
        if (s.isNotBlank()) { ensureRunning(); sentences.trySend(s) }
        if (accepting) sentences.close()
        accepting = false
    }

    /** Barge-in: stop talking right now and drop everything queued. */
    fun stop() {
        accepting = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        pending.setLength(0)
        sentences.cancel(); audio.cancel()
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        _speaking.value = false
    }

    private var accepting = false
    private var sentFirst = false        // first chunk of a reply may be a clause, to start talking sooner
    private var firstFeedAt = 0L         // for the "first audio" latency log
    private var firstAudioLogged = false

    /**
     * For the very first chunk of a reply: end at a comma / dash / colon once there are ≥ 4 words,
     * so Ronie starts talking before the whole first sentence is written.
     */
    private fun firstClauseEnd(sb: StringBuilder): Int? {
        for (i in sb.indices) {
            val c = sb[i]
            if ((c == ',' || c == '—' || c == ':' || c == ';') && sb.getOrNull(i + 1)?.isWhitespace() == true) {
                if (sb.substring(0, i).trim().split(Regex("\\s+")).size >= 4) return i + 1
            }
        }
        return null
    }

    private fun ensureRunning() {
        if (accepting && jobs.any { it.isActive }) return
        if (jobs.any { it.isActive }) stop()    // a finished reply is still playing: a new one replaces it
        accepting = true
        sentFirst = false; firstFeedAt = 0L; firstAudioLogged = false
        sentences = Channel(Channel.UNLIMITED)
        audio = Channel(2)
        val s = sentences; val a = audio
        val synth = scope.launch(Dispatchers.Default) {
            for (text in s) {
                val t0 = System.currentTimeMillis()
                val ps = runCatching { g2p.phonemize(text) }.getOrElse { Log.e("RonieVoice", "g2p failed", it); "" }
                if (ps.isBlank()) continue
                val wav = runCatching { tts.synth(ps, speed) }.getOrElse { Log.e("RonieVoice", "tts failed", it); continue }
                Log.i("RonieVoice", "tts ${System.currentTimeMillis() - t0} ms for ${wav.size / 24} ms audio | $text | $ps")
                a.send(wav)
            }
            a.close()
        }
        val play = scope.launch(Dispatchers.IO) {
            val t = newTrack().also { track = it }
            try {
                for (wav in a) {
                    if (!isActive) break
                    _speaking.value = true
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    if (!firstAudioLogged && firstFeedAt > 0) {
                        firstAudioLogged = true
                        Log.i("RonieVoice", "first audio ${System.currentTimeMillis() - firstFeedAt} ms after the reply started")
                    }
                    var off = 0
                    while (off < wav.size && isActive) {
                        val n = t.write(wav, off, wav.size - off, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n
                    }
                }
                // Let the tail play out, then release.
                if (isActive) { Thread.sleep(250); t.stop() }
            } finally {
                runCatching { t.release() }
                if (track === t) track = null
                _speaking.value = false
            }
        }
        jobs = listOf(synth, play)
    }

    private fun newTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(Paradee.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(Paradee.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT) * 4)
        .build()

    /** End index of the first complete sentence (., !, ? or newline followed by space/end), or null. */
    private fun sentenceEnd(sb: StringBuilder): Int? {
        for (i in sb.indices) {
            val c = sb[i]
            if (c == '\n' && i > 0) return i + 1
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                val next = sb.getOrNull(i + 1) ?: return null          // wait: maybe "3." of "3.5"
                if (next.isWhitespace() || next == '"' || next == '”') {
                    // Don't split "e.g." / "Dr." / "3. " list numbers that are tiny fragments.
                    val start = sb.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), i - 1).let { if (it < 0) 0 else it + 1 }
                    if (i - start < 4 && c == '.') continue
                    return i + 1
                }
            }
        }
        // Very long run-on text: speak at a comma rather than wait forever.
        if (sb.length > 220) sb.lastIndexOf(", ").takeIf { it > 60 }?.let { return it + 1 }
        return null
    }

    private fun StringBuilder.lastIndexOfAny(chars: CharArray, from: Int): Int {
        for (i in from downTo 0) if (this[i] in chars) return i
        return -1
    }
}
