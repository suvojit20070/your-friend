package com.suvo.agent.core.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** WAV (RIFF) reader/writer: PCM 8/16/24/32-bit and IEEE float 32, any channel count (mixed to mono). */
public final class Wav {

    private Wav() {
    }

    public static Pcm decode(byte[] data) throws IOException {
        if (data.length < 12) throw new IOException("Not a WAV file (too short)");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new IOException("Not a RIFF/WAVE file");
        int pos = 12;
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int dataOff = -1;
        int dataLen = 0;
        while (pos + 8 <= data.length) {
            int id = b.getInt(pos);
            int len = b.getInt(pos + 4);
            int body = pos + 8;
            if (len < 0) throw new IOException("Corrupt WAV chunk");
            if (id == 0x20746d66) { // "fmt "
                format = b.getShort(body) & 0xffff;
                channels = b.getShort(body + 2) & 0xffff;
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14) & 0xffff;
                if (format == 0xFFFE && len >= 26) format = b.getShort(body + 24) & 0xffff; // WAVE_FORMAT_EXTENSIBLE
            } else if (id == 0x61746164) { // "data"
                dataOff = body;
                // Streaming writers sometimes leave the size as 0 or -1.
                dataLen = (len == 0 || body + len > data.length) ? data.length - body : len;
                break;
            }
            pos = body + len + (len & 1);
        }
        if (format < 0 || dataOff < 0) throw new IOException("WAV is missing fmt or data chunk");
        if (channels <= 0 || rate <= 0) throw new IOException("Invalid WAV header");
        if (format != 1 && format != 3) throw new IOException("Unsupported WAV encoding (format " + format + "); use PCM or float");
        int bytesPer = bits / 8;
        if (bytesPer <= 0) throw new IOException("Invalid bit depth");
        int frames = dataLen / (bytesPer * channels);
        float[] out = new float[frames];
        int p = dataOff;
        for (int f = 0; f < frames; f++) {
            float acc = 0;
            for (int c = 0; c < channels; c++) {
                float v;
                if (format == 3 && bits == 32) v = b.getFloat(p);
                else if (format == 3 && bits == 64) v = (float) b.getDouble(p);
                else if (bits == 8) v = ((data[p] & 0xff) - 128) / 128f;
                else if (bits == 16) v = b.getShort(p) / 32768f;
                else if (bits == 24) {
                    int s = (data[p] & 0xff) | ((data[p + 1] & 0xff) << 8) | (data[p + 2] << 16);
                    v = s / 8388608f;
                } else if (bits == 32) v = b.getInt(p) / 2147483648f;
                else throw new IOException("Unsupported bit depth " + bits);
                acc += v;
                p += bytesPer;
            }
            out[f] = Math.max(-1f, Math.min(1f, acc / channels));
        }
        return new Pcm(out, rate);
    }

    /** Encodes mono 16-bit PCM WAV. */
    public static byte[] encode16(Pcm pcm) {
        int n = pcm.samples.length;
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + n * 2).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1).putInt(pcm.sampleRate)
                .putInt(pcm.sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(n * 2);
        for (float s : pcm.samples) b.putShort((short) Math.round(Math.max(-1f, Math.min(1f, s)) * 32767));
        return b.array();
    }

    public static byte[] readAll(java.io.InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
            if (bos.size() > max) throw new IOException("Audio is too large");
        }
        return bos.toByteArray();
    }
}
