package pl.whisperfiles;

import android.content.ContentResolver;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.whispercpp.java.whisper.WhisperContext;
import com.whispercpp.java.whisper.WhisperSegment;
import com.whispercpp.java.whisper.WhisperWord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioTranscriber {
    private static final int TARGET_RATE = 16000;
    private static final int CHUNK_SECONDS = 30;
    private static final int OVERLAP_SECONDS = 2;
    private static final int CHUNK_SAMPLES = TARGET_RATE * CHUNK_SECONDS;
    private static final int OVERLAP_SAMPLES = TARGET_RATE * OVERLAP_SECONDS;
    private static final long OVERLAP_MS = OVERLAP_SECONDS * 1000L;

    interface Callback {
        void onProgress(int percent, String status);
        void onSegment(long startMs, long endMs, String text, List<SubtitleWord> words) throws IOException;
    }

    private AudioTranscriber() {}

    static void transcribe(
            ContentResolver resolver,
            Uri mediaUri,
            WhisperContext whisper,
            AtomicBoolean cancelled,
            Callback callback) throws Exception {

        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(mediaUri, "r")) {
            if (pfd == null) throw new IOException("Nie można otworzyć pliku audio/wideo");

            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(pfd.getFileDescriptor());
                int audioTrack = findAudioTrack(extractor);
                if (audioTrack < 0) throw new IOException("Plik nie zawiera obsługiwanej ścieżki audio");

                extractor.selectTrack(audioTrack);
                MediaFormat inputFormat = extractor.getTrackFormat(audioTrack);
                String mime = inputFormat.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) {
                    throw new IOException("Nieprawidłowy format ścieżki audio");
                }

                long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                        ? inputFormat.getLong(MediaFormat.KEY_DURATION) : -1L;

                SegmentEmitter emitter = new SegmentEmitter(callback);
                Chunker chunker = new Chunker((audio, startSample, firstChunk) -> {
                    if (cancelled.get()) return;
                    callback.onProgress(progressFromSample(startSample, durationUs), "Transkrypcja…");
                    List<WhisperSegment> segments = whisper.transcribe(audio, "pl");
                    if (cancelled.get()) return;
                    long chunkStartMs = startSample * 1000L / TARGET_RATE;
                    for (WhisperSegment segment : segments) {
                        long localStartMs = segment.getStart() * 10L;
                        long localEndMs = segment.getEnd() * 10L;
                        if (!firstChunk && localEndMs <= OVERLAP_MS) continue;
                        ArrayList<SubtitleWord> timedWords = new ArrayList<>();
                        for (WhisperWord word : segment.getWords()) {
                            long wordStart = word.getStart() * 10L;
                            long wordEnd = word.getEnd() * 10L;
                            if (!firstChunk && wordEnd <= OVERLAP_MS) continue;
                            timedWords.add(new SubtitleWord(
                                    chunkStartMs + wordStart,
                                    chunkStartMs + wordEnd,
                                    word.getText()));
                        }
                        emitter.emit(
                                chunkStartMs + localStartMs,
                                chunkStartMs + localEndMs,
                                segment.getText(),
                                timedWords);
                    }
                });

                if ("audio/raw".equalsIgnoreCase(mime)) {
                    decodeRaw(extractor, inputFormat, durationUs, cancelled, callback, chunker);
                } else {
                    decodeWithCodec(extractor, inputFormat, mime, durationUs, cancelled, callback, chunker);
                }

                if (!cancelled.get()) {
                    chunker.finish();
                    callback.onProgress(100, "Gotowe");
                }
            } finally {
                extractor.release();
            }
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static void decodeRaw(
            MediaExtractor extractor,
            MediaFormat format,
            long durationUs,
            AtomicBoolean cancelled,
            Callback callback,
            Chunker chunker) throws Exception {

        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                : AudioFormat.ENCODING_PCM_16BIT;

        PcmConverter converter = new PcmConverter(sampleRate, channels, encoding, chunker);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN);

        while (!cancelled.get()) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            long ptsUs = extractor.getSampleTime();
            buffer.position(0);
            buffer.limit(size);
            converter.accept(buffer);
            reportDecodeProgress(ptsUs, durationUs, callback);
            extractor.advance();
        }
    }

    private static void decodeWithCodec(
            MediaExtractor extractor,
            MediaFormat inputFormat,
            String mime,
            long durationUs,
            AtomicBoolean cancelled,
            Callback callback,
            Chunker chunker) throws Exception {

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        try {
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            PcmConverter converter = new PcmConverter(sampleRate, channels, encoding, chunker);

            while (!outputDone && !cancelled.get()) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("Brak bufora wejściowego dekodera");
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long ptsUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, extractor.getSampleFlags());
                            reportDecodeProgress(ptsUs, durationUs, callback);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    sampleRate = out.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? out.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sampleRate;
                    channels = out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                    encoding = out.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? out.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    converter = new PcmConverter(sampleRate, channels, encoding, chunker);
                } else if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0) {
                        int oldLimit = output.limit();
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        converter.accept(output.slice().order(ByteOrder.LITTLE_ENDIAN));
                        output.limit(oldLimit);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release();
        }
    }

    private static void reportDecodeProgress(long ptsUs, long durationUs, Callback callback) {
        if (durationUs <= 0 || ptsUs < 0) return;
        int percent = (int) Math.max(0, Math.min(99, (ptsUs * 100L) / durationUs));
        callback.onProgress(percent, "Dekodowanie / transkrypcja…");
    }

    private static int progressFromSample(long startSample, long durationUs) {
        if (durationUs <= 0) return 0;
        long ms = startSample * 1000L / TARGET_RATE;
        return (int) Math.max(0, Math.min(99, (ms * 100000L) / durationUs));
    }

    private interface FloatSink { void onSample(float sample) throws Exception; }

    private static final class PcmConverter {
        private final int channels;
        private final int encoding;
        private final Resampler resampler;

        PcmConverter(int sampleRate, int channels, int encoding, FloatSink sink) {
            if (sampleRate <= 0 || channels <= 0) throw new IllegalArgumentException("Błędny format PCM");
            this.channels = channels;
            this.encoding = encoding;
            this.resampler = new Resampler(sampleRate, TARGET_RATE, sink);
        }

        void accept(ByteBuffer data) throws Exception {
            data.order(ByteOrder.LITTLE_ENDIAN);
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                int frames = data.remaining() / (4 * channels);
                for (int i = 0; i < frames; i++) {
                    float mono = 0f;
                    for (int c = 0; c < channels; c++) mono += clamp(data.getFloat());
                    resampler.push(mono / channels);
                }
            } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                int frames = data.remaining() / channels;
                for (int i = 0; i < frames; i++) {
                    float mono = 0f;
                    for (int c = 0; c < channels; c++) mono += ((data.get() & 0xff) - 128) / 128f;
                    resampler.push(mono / channels);
                }
            } else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                int frames = data.remaining() / (3 * channels);
                for (int i = 0; i < frames; i++) {
                    float mono = 0f;
                    for (int c = 0; c < channels; c++) {
                        int v = (data.get() & 0xff) | ((data.get() & 0xff) << 8) | (data.get() << 16);
                        mono += v / 8388608f;
                    }
                    resampler.push(mono / channels);
                }
            } else if (encoding == AudioFormat.ENCODING_PCM_32BIT) {
                int frames = data.remaining() / (4 * channels);
                for (int i = 0; i < frames; i++) {
                    float mono = 0f;
                    for (int c = 0; c < channels; c++) mono += data.getInt() / 2147483648f;
                    resampler.push(mono / channels);
                }
            } else {
                int frames = data.remaining() / (2 * channels);
                for (int i = 0; i < frames; i++) {
                    float mono = 0f;
                    for (int c = 0; c < channels; c++) mono += data.getShort() / 32768f;
                    resampler.push(mono / channels);
                }
            }
        }

        private float clamp(float value) {
            return Math.max(-1f, Math.min(1f, value));
        }
    }

    private static final class Resampler {
        private final double sourcePerTarget;
        private final FloatSink sink;
        private boolean havePrevious;
        private float previous;
        private long nextInputIndex;
        private double nextOutputPosition;

        Resampler(int sourceRate, int targetRate, FloatSink sink) {
            this.sourcePerTarget = sourceRate / (double) targetRate;
            this.sink = sink;
        }

        void push(float sample) throws Exception {
            if (!havePrevious) {
                havePrevious = true;
                previous = sample;
                sink.onSample(sample);
                nextOutputPosition = sourcePerTarget;
                nextInputIndex = 1;
                return;
            }

            long currentIndex = nextInputIndex;
            while (nextOutputPosition <= currentIndex + 1e-9) {
                double fraction = nextOutputPosition - (currentIndex - 1.0);
                float out = (float) (previous + (sample - previous) * fraction);
                sink.onSample(out);
                nextOutputPosition += sourcePerTarget;
            }
            previous = sample;
            nextInputIndex++;
        }
    }

    private interface ChunkProcessor {
        void process(float[] audio, long startSample, boolean firstChunk) throws Exception;
    }

    private static final class Chunker implements FloatSink {
        private final ChunkProcessor processor;
        private final float[] buffer = new float[CHUNK_SAMPLES];
        private int size;
        private long chunkStartSample;
        private int chunksProcessed;

        Chunker(ChunkProcessor processor) {
            this.processor = processor;
        }

        @Override
        public void onSample(float sample) throws Exception {
            buffer[size++] = sample;
            if (size == CHUNK_SAMPLES) processFullChunk();
        }

        private void processFullChunk() throws Exception {
            float[] audio = new float[size];
            System.arraycopy(buffer, 0, audio, 0, size);
            processor.process(audio, chunkStartSample, chunksProcessed == 0);
            chunksProcessed++;

            System.arraycopy(buffer, size - OVERLAP_SAMPLES, buffer, 0, OVERLAP_SAMPLES);
            chunkStartSample += size - OVERLAP_SAMPLES;
            size = OVERLAP_SAMPLES;
        }

        void finish() throws Exception {
            if (chunksProcessed == 0) {
                if (size > 0) processTail();
            } else if (size > OVERLAP_SAMPLES) {
                processTail();
            }
        }

        private void processTail() throws Exception {
            float[] audio = new float[size];
            System.arraycopy(buffer, 0, audio, 0, size);
            processor.process(audio, chunkStartSample, chunksProcessed == 0);
            chunksProcessed++;
        }
    }

    private static final class SegmentEmitter {
        private final Callback callback;
        private String lastNormalized = "";
        private long lastEndMs;

        SegmentEmitter(Callback callback) {
            this.callback = callback;
        }

        void emit(long startMs, long endMs, String text, List<SubtitleWord> words) throws IOException {
            String clean = text == null ? "" : text.trim();
            if (clean.isEmpty()) return;
            String normalized = clean.toLowerCase(Locale.ROOT)
                    .replaceAll("[\\p{Punct}\\s]+", " ")
                    .trim();

            boolean nearPrevious = startMs <= lastEndMs + 3500L;
            if (nearPrevious && normalized.length() >= 6 &&
                    (normalized.equals(lastNormalized) || lastNormalized.endsWith(normalized))) {
                return;
            }

            long safeStart = Math.max(0L, startMs);
            long safeEnd = Math.max(safeStart + 10L, endMs);
            ArrayList<SubtitleWord> safeWords = new ArrayList<>();
            if (words != null) {
                for (SubtitleWord word : words) {
                    if (word == null || word.text.isEmpty()) continue;
                    if (lastEndMs > 0 && word.endMs <= lastEndMs - 120L) continue;
                    safeWords.add(word);
                }
            }
            if (safeWords.isEmpty()) {
                safeWords.addAll(SubtitleTimelineStore.wordsFromText(clean, safeStart, safeEnd));
            }
            callback.onSegment(safeStart, safeEnd, clean, safeWords);
            lastNormalized = normalized;
            lastEndMs = Math.max(lastEndMs, safeEnd);
        }
    }
}
