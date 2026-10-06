package com.github.cuixudong74.containerimagelayeranalyzerjava.testutil;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Builds well-formed (or deliberately malformed) tar archives in memory for tests. */
public final class TarBuilder {

    public record Entry(String name, char type, byte[] content, String linkName) { }

    private final List<Entry> entries = new ArrayList<>();

    public TarBuilder file(String name, byte[] content) {
        entries.add(new Entry(name, '0', content, ""));
        return this;
    }

    public TarBuilder file(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8));
    }

    public TarBuilder dir(String name) {
        entries.add(new Entry(name, '5', new byte[0], ""));
        return this;
    }

    public TarBuilder symlink(String name, String target) {
        entries.add(new Entry(name, '2', new byte[0], target));
        return this;
    }

    public TarBuilder hardlink(String name, String target) {
        entries.add(new Entry(name, '1', new byte[0], target));
        return this;
    }

    public TarBuilder raw(String name, char type, byte[] content, String linkName) {
        entries.add(new Entry(name, type, content, linkName));
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Entry e : entries) {
            byte[] header = header(e.name(), e.type(), e.content().length, e.linkName());
            out.write(header, 0, header.length);
            out.write(e.content(), 0, e.content().length);
            int pad = (int) ((512 - (e.content().length % 512)) % 512);
            out.write(new byte[pad], 0, pad);
        }
        out.write(new byte[1024], 0, 1024); // two zero blocks
        return out.toByteArray();
    }

    public static byte[] header(String name, char type, long size, String linkName) {
        byte[] h = new byte[512];
        writeStr(h, 0, 100, name);
        writeStr(h, 100, 8, "0000644\0");
        writeStr(h, 108, 8, "0000000\0");
        writeStr(h, 116, 8, "0000000\0");
        writeStr(h, 124, 12, String.format("%011o\0", size));
        writeStr(h, 136, 12, String.format("%011o\0", 1700000000));
        writeStr(h, 148, 8, "        ");
        h[156] = (byte) type;
        writeStr(h, 157, 100, linkName);
        writeStr(h, 257, 8, "ustar\0" + "00");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        writeStr(h, 148, 8, String.format("%06o\0 ", sum));
        return h;
    }

    private static void writeStr(byte[] h, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, len));
    }
}
