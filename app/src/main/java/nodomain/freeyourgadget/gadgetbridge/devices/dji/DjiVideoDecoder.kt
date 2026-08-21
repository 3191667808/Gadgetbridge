package nodomain.freeyourgadget.gadgetbridge.devices.dji

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.SurfaceHolder
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb.DjiCommandReceiver
import nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils
import org.slf4j.LoggerFactory

/**
 * Owns the MediaCodec H.264 decoder backing [DjiVideoActivity]'s SurfaceView.
 *
 * Feed it Annex-B NAL units as they arrive from the DJI video stream via [feedNal]. Register it
 * as the SurfaceView's [SurfaceHolder.Callback] so it can create and tear down the codec in step
 * with the surface.
 *
 * FIXME: This feels over-engineered and is still slightly unreliable, with a lot of streaming artifacts.
 */
class DjiVideoDecoder : SurfaceHolder.Callback {
    // Guards codec/sawSps/sawPps. surfaceCreated/surfaceDestroyed run on the main thread while
    // feedNal runs on the USB read thread, so without a lock feedNal could grab a codec reference
    // just as surfaceDestroyed stops and releases it, and keep calling into it after that.
    private val codecLock = Any()
    private var codec: MediaCodec? = null

    // Blocks feeding slice/SEI/AUD data to the decoder until a fresh SPS+PPS pair has been seen -
    // both after (re)creating the codec and after a flush() recovery, since flush() also discards
    // any parameter sets fed so far. The stream doesn't send SPS/PPS right at the start of a
    // connection, so without this gate the decoder would be fed frames it can't interpret yet.
    private var sawSps = false
    private var sawPps = false

    // Whether a keyframe has already been requested (see feedNal) for the current sawSps/sawPps
    // generation - so the request fires at most once per (re)connect/recovery instead of once per
    // dropped NAL, but still fires again after the next reset if the first request went nowhere
    // (e.g. the aircraft link wasn't up yet).
    private var keyframeRequested = false

    // How many frames are currently inside MediaCodec - queued via queueInputBuffer but not yet
    // back via onOutputBufferAvailable. feedNal()'s own backlog (pendingNals) only tracks NALs
    // waiting for a free *input* buffer, which MediaCodec usually has several of - so it stays
    // small even while the codec's internal decode pipeline is backed up. This is what actually
    // shows that backlog: if it stays elevated, the codec itself can't keep up with the input
    // rate, which is exactly what a large gap between DjiPipelineTrace.decoderQueueWait (small)
    // and DjiPipelineTrace.decoderLatency (large) implies but can't directly show.
    private var framesInFlight = 0

    // Matches codec input buffers with pending NAL data as each becomes available: the codec's
    // own callback thread hands us a free buffer index via onInputBufferAvailable, and the USB
    // read thread hands us NAL bytes via feedNal. Whichever arrives first waits here for the
    // other, so a NAL is never dropped just because no input buffer was free at that instant.
    @Suppress("ArrayInDataClass")
    private data class PendingNal(val data: ByteArray, val flags: Int, val queuedAtNanos: Long = System.nanoTime())

    private val pendingNals = ArrayDeque<PendingNal>()
    private val availableInputIndices = ArrayDeque<Int>()

    override fun surfaceCreated(holder: SurfaceHolder) = synchronized(codecLock) {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT)
            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            LOG.debug("Using decoder {}", newCodec.name)
            // setCallback() must be called before configure() to enable asynchronous mode.
            newCodec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    this@DjiVideoDecoder.onInputBufferAvailable(mc, index)
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    this@DjiVideoDecoder.onOutputBufferAvailable(mc, index, info)
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    onCodecError(mc, e)
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    LOG.debug("Video decoder output format changed: {}", format)
                }
            })
            newCodec.configure(format, holder.surface, null, 0)
            newCodec.start()
            codec = newCodec
            sawSps = false
            sawPps = false
            keyframeRequested = false
            framesInFlight = 0
            pendingNals.clear()
            availableInputIndices.clear()
        } catch (e: Exception) {
            LOG.error("Failed to create video decoder", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = synchronized(codecLock) {
        val c = codec
        codec = null
        framesInFlight = 0
        pendingNals.clear()
        availableInputIndices.clear()
        if (c != null) {
            try {
                c.stop()
            } catch (e: Exception) {
                LOG.warn("Error stopping video decoder", e)
            }
            c.release()
        }
    }

    fun feedNal(nal: ByteArray) = synchronized(codecLock) {
        val c = codec ?: return@synchronized
        val type = nalType(nal)
        if (type == null) {
            // Not valid Annex-B - drop it rather than feed garbage to the decoder.
            LOG.warn("Dropping non-Annex-B chunk ({} bytes)", nal.size)
            return@synchronized
        }

        // SPS/PPS must be flagged as codec config, not fed as ordinary slice data, or the
        // decoder won't pick up the parameter sets and its output will be corrupted. They're fed
        // through unconditionally, as soon as each arrives - unlike everything else below, gating
        // *them* on sawSps/sawPps would mean the first SPS of a session is always discarded
        // (sawPps is still false at the instant it arrives, so it would fail its own gate) and is
        // never seen by the decoder again.
        val isParameterSet = type == NAL_TYPE_SPS || type == NAL_TYPE_PPS
        if (isParameterSet) {
            if (type == NAL_TYPE_SPS) sawSps = true else sawPps = true
        } else if (!sawSps || !sawPps) {
            // Nothing is decodable before the first SPS+PPS pair - see [sawSps]. This is also
            // the right moment (not any earlier) to ask the camera for one: video traffic is
            // demonstrably flowing right now, so the request has an aircraft link to land on -
            // asking earlier, e.g. right when the codec/surface is (re)created, risks firing
            // before that link even exists. Rate-limited to once per sawSps/sawPps generation via
            // keyframeRequested, since this branch can otherwise run dozens of times a second.
            if (!keyframeRequested) {
                keyframeRequested = true
                DjiCommandReceiver.requestKeyframe()
            }
            LOG.warn("Got NAL type {} before first SPS/PSS", type)
            return@synchronized
        }

        val flags = if (isParameterSet) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

        val availableIndex = availableInputIndices.removeFirstOrNull()
        if (availableIndex != null) {
            feedBuffer(c, availableIndex, nal, flags)
        } else {
            // No input buffer free right now - queue it for the next onInputBufferAvailable.
            // Bounded so a decoder that's persistently behind doesn't grow this without limit.
            pendingNals.addLast(PendingNal(nal, flags))
            if (pendingNals.size > MAX_PENDING_NALS) {
                pendingNals.removeFirst()
                LOG.warn("Input backlog exceeded {} NALs, dropping oldest", MAX_PENDING_NALS)
            }
        }
    }

    private fun onInputBufferAvailable(mc: MediaCodec, index: Int) = synchronized(codecLock) {
        if (mc !== codec) return@synchronized // stale callback from a since-torn-down codec
        val pending = pendingNals.removeFirstOrNull()
        if (pending == null) {
            availableInputIndices.addLast(index)
        } else {
            val waitNanos = System.nanoTime() - pending.queuedAtNanos
            val queuedMs = waitNanos / 1_000_000
            if (queuedMs > INPUT_QUEUE_LATENCY_WARN_MS) {
                // A NAL sitting here this long means the codec's input side is the bottleneck,
                // not anything upstream (USB read/process, envelope/NAL reassembly) - see the
                // video-latency investigation this was added for.
                LOG.warn("NAL waited {} ms for a free input buffer ({} still queued)", queuedMs, pendingNals.size)
            }
            feedBuffer(mc, index, pending.data, pending.flags)
        }
    }

    /** Must be called with [codecLock] held. */
    private fun feedBuffer(c: MediaCodec, index: Int, nal: ByteArray, flags: Int) {
        try {
            val inputBuffer = c.getInputBuffer(index)
            inputBuffer?.clear()
            inputBuffer?.put(nal)
            c.queueInputBuffer(index, 0, nal.size, System.nanoTime() / 1000, flags)
            framesInFlight++
        } catch (e: Exception) {
            LOG.warn("Error queueing input buffer, attempting to recover", e)
            recoverCodec(c)
        }
    }

    private fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = synchronized(codecLock) {
        if (mc !== codec) return@synchronized // stale callback from a since-torn-down codec
        if (framesInFlight > 0) framesInFlight--
        try {
            // feedBuffer() stamps presentationTimeUs with the wall-clock time each NAL was
            // queued (not real presentation timing - this stream has no A/V sync to preserve),
            // so this is the round-trip time from "queued as codec input" to "ready to render":
            // covers actual codec decode time, not just the input-side wait already covered by
            // the warning in onInputBufferAvailable.
            val latencyUs = System.nanoTime() / 1000 - info.presentationTimeUs
            val codecLatencyMs = latencyUs / 1000
            if (codecLatencyMs > CODEC_LATENCY_WARN_MS) {
                LOG.warn("Frame took {} ms from queued input to ready output", codecLatencyMs)
            }
            mc.releaseOutputBuffer(index, true)
        } catch (e: Exception) {
            LOG.warn("Error releasing output buffer, attempting to recover", e)
            recoverCodec(mc)
        }
    }

    private fun onCodecError(mc: MediaCodec, e: MediaCodec.CodecException) = synchronized(codecLock) {
        if (mc !== codec) return@synchronized // stale callback from a since-torn-down codec
        LOG.warn("Video decoder error, attempting to recover", e)
        recoverCodec(mc)
    }

    /**
     * Tries MediaCodec's cheap recovery path, flush(), first. If that also fails, the codec is
     * treated as dead and dropped, so callers stop touching it until surfaceCreated supplies a
     * new one, instead of retrying the same broken codec on every frame. Must hold [codecLock].
     */
    private fun recoverCodec(c: MediaCodec) {
        try {
            c.flush()
            sawSps = false
            sawPps = false
            keyframeRequested = false
            framesInFlight = 0
            pendingNals.clear()
            availableInputIndices.clear()
            LOG.info("Video decoder flushed and recovered")
        } catch (flushException: Exception) {
            LOG.warn("Video decoder unrecoverable, releasing it", flushException)
            try {
                c.release()
            } catch (_: Exception) {
            }
            if (codec === c) codec = null
            pendingNals.clear()
            availableInputIndices.clear()
        }
    }

    /**
     * Returns the Annex-B NAL unit type (bits 0-4 of the header byte right
     * after the start code), or null if [nal] doesn't start with a valid
     * start code followed by at least one header byte - see [feedNal].
     */
    private fun nalType(nal: ByteArray): Int? {
        if (nal.size >= 5 && ArrayUtils.startsWith(nal, NAL_START_1)) {
            return nal[4].toInt() and 0x1F
        }
        if (nal.size >= 4 && ArrayUtils.startsWith(nal, NAL_START_2)) {
            return nal[3].toInt() and 0x1F
        }
        return null
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DjiVideoDecoder::class.java)

        // FIXME: This should not be hardcoded
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720

        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8

        // Roughly 1-2s of backlog at typical frame rates. See [pendingNals].
        private const val MAX_PENDING_NALS = 30

        // Thresholds for the latency warnings in onInputBufferAvailable/onOutputBufferAvailable -
        // deliberately coarse (not logged on every frame) so they flag a real, sustained problem
        // rather than normal jitter.
        private const val INPUT_QUEUE_LATENCY_WARN_MS = 200
        private const val CODEC_LATENCY_WARN_MS = 200

        private val NAL_START_1 = byteArrayOf(0, 0, 0, 1)
        private val NAL_START_2 = byteArrayOf(0, 0, 1)
    }
}
