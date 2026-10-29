package com.bocbang.backend.stt;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Reads and slices 16-bit little-endian mono PCM WAV files on disk. */
public final class Wav {

    public static final int HEADER_BYTES = 44;

    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;
    private static final float PCM16_SCALE = 1.0f / 32768.0f;

    private Wav() {
    }

    public record Info(int format, int sampleRate, int channels, int bitsPerSample, long dataOffset, long dataLength) {

        public int bytesPerFrame() {
            return channels * bitsPerSample / 8;
        }

        public double durationSeconds() {
            return dataLength / (double) (sampleRate * bytesPerFrame());
        }

        public boolean isPcm16Mono(int expectedSampleRate) {
            return (format == FORMAT_PCM || format == FORMAT_EXTENSIBLE)
                    && bitsPerSample == 16 && channels == 1 && sampleRate == expectedSampleRate;
        }
    }

    /** Canonical 44-byte header for 16-bit mono PCM. */
    public static byte[] header(long dataLength, int sampleRate) {
        if (dataLength > Integer.MAX_VALUE - 36) {
            throw new IllegalArgumentException("WAV data is too large: " + dataLength + " bytes");
        }
        int blockAlign = 2;
        return ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(ascii("RIFF"))
                .putInt(36 + (int) dataLength)
                .put(ascii("WAVE"))
                .put(ascii("fmt "))
                .putInt(16)
                .putShort((short) FORMAT_PCM)
                .putShort((short) 1)
                .putInt(sampleRate)
                .putInt(sampleRate * blockAlign)
                .putShort((short) blockAlign)
                .putShort((short) 16)
                .put(ascii("data"))
                .putInt((int) dataLength)
                .array();
    }

    public static Info read(Path wav) throws IOException {
        try (FileChannel channel = FileChannel.open(wav, StandardOpenOption.READ)) {
            ByteBuffer riff = readFully(channel, 0, 12);
            if (!"RIFF".equals(ascii(riff, 0)) || !"WAVE".equals(ascii(riff, 8))) {
                throw new IOException("Not a RIFF/WAVE file: " + wav.getFileName());
            }
            ByteBuffer fmt = null;
            long position = 12;
            while (position + 8 <= channel.size()) {
                ByteBuffer chunk = readFully(channel, position, 8);
                String id = ascii(chunk, 0);
                long size = Integer.toUnsignedLong(chunk.getInt(4));
                if ("fmt ".equals(id)) {
                    fmt = readFully(channel, position + 8, 16);
                } else if ("data".equals(id)) {
                    if (fmt == null) {
                        throw new IOException("WAV data chunk appears before fmt chunk");
                    }
                    // Streamed WAVs may carry a placeholder size; trust the file length instead.
                    long length = Math.min(size, channel.size() - position - 8);
                    return new Info(fmt.getShort(0) & 0xFFFF, fmt.getInt(4), fmt.getShort(2), fmt.getShort(14),
                            position + 8, length);
                }
                position += 8 + size + (size & 1);
            }
            throw new IOException("WAV file has no data chunk");
        }
    }

    /** Converts 16-bit little-endian mono PCM bytes into normalized floats in {@code [-1.0, 1.0)}. */
    public static float[] toFloats(byte[] pcmS16le, int length) {
        int samples = length / 2;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            short sample = (short) ((pcmS16le[i * 2] & 0xFF) | (pcmS16le[i * 2 + 1] << 8));
            out[i] = sample * PCM16_SCALE;
        }
        return out;
    }

    /** Reads all samples of a 16-bit mono WAV file as normalized floats. */
    public static float[] readAllFloats(Path wav) throws IOException {
        Info info = read(wav);
        return readFloats(wav, info, 0, info.durationSeconds());
    }

    /** Reads the samples between two timestamps of {@code source} directly into a {@code float[]} array. */
    public static float[] readFloats(Path source, Info info, double startSeconds, double endSeconds) throws IOException {
        long frame = info.bytesPerFrame();
        long from = clamp(Math.round(startSeconds * info.sampleRate()) * frame, 0, info.dataLength());
        long to = clamp(Math.round(endSeconds * info.sampleRate()) * frame, from, info.dataLength());
        long byteCount = to - from;
        if (byteCount <= 0) {
            return new float[0];
        }
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Audio slice is too large to fit in a single array: " + byteCount);
        }
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer raw = readFully(channel, info.dataOffset() + from, (int) byteCount);
            int samples = (int) (byteCount / 2);
            float[] out = new float[samples];
            for (int i = 0; i < samples; i++) {
                out[i] = raw.getShort() * PCM16_SCALE;
            }
            return out;
        }
    }

    /** Writes the audio between two timestamps of {@code source} as a standalone WAV file. */
    public static void writeSlice(Path source, Info info, double startSeconds, double endSeconds, Path target)
            throws IOException {
        long frame = info.bytesPerFrame();
        long from = clamp(Math.round(startSeconds * info.sampleRate()) * frame, 0, info.dataLength());
        long to = clamp(Math.round(endSeconds * info.sampleRate()) * frame, from, info.dataLength());
        copy(source, info.dataOffset() + from, to - from, target, header(to - from, info.sampleRate()));
    }

    private static void copy(Path source, long offset, long length, Path target, byte[] header) throws IOException {
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            if (header != null) {
                out.write(ByteBuffer.wrap(header));
            }
            long copied = 0;
            while (copied < length) {
                long n = in.transferTo(offset + copied, length - copied, out);
                if (n <= 0) {
                    throw new EOFException("Unexpected end of " + source.getFileName());
                }
                copied += n;
            }
        }
    }

    private static ByteBuffer readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new EOFException("Truncated WAV file");
            }
        }
        return buffer.flip();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String ascii(ByteBuffer buffer, int offset) {
        byte[] bytes = new byte[4];
        buffer.get(offset, bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
