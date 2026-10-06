package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streaming tar writer with deterministic output: fixed metadata (mtime 0,
 * uid/gid 0, stable modes), PAX extended headers for over-long names. Content
 * is copied through a caller-supplied fixed-size buffer, so memory stays
 * bounded regardless of entry size.
 */
public final class TarWriter implements AutoCloseable {

    private static final int BLOCK = 512;

    private final OutputStream out;
    private final boolean writeEndMarkers;
    private boolean finished;

    public TarWriter(OutputStream out) {
        this(out, true);
    }

    /**
     * @param writeEndMarkers when false, close() does not emit the two zero
     *                        blocks, allowing another pass to append more
     *                        entries to the same archive.
     */
    public TarWriter(OutputStream out, boolean writeEndMarkers) {
        this.out = out;
        this.writeEndMarkers = writeEndMarkers;
    }

    /** Writes a file header; content must follow via {@link #writeContent}. */
    public void putFile(String path, long size) throws IOException {
        put('0', path, size, null);
    }

    public void putDirectory(String path) throws IOException {
        put('5', path, 0, null);
    }

    public void putSymlink(String path, String target) throws IOException {
        put('2', path, 0, target);
    }

    public void putHardlink(String path, String target) throws IOException {
        put('1', path, 0, target);
    }

    /** Streams exactly {@code size} bytes of content for the current entry. */
    public void writeContent(InputStream in, long size, byte[] buffer) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new IOException("unexpected end of content stream");
            }
            out.write(buffer, 0, n);
            remaining -= n;
        }
        int pad = (int) ((BLOCK - (size % BLOCK)) % BLOCK);
        if (pad > 0) {
            out.write(new byte[pad]);
        }
    }

    @Override
    public void close() throws IOException {
        if (!finished) {
            finished = true;
            if (writeEndMarkers) {
                out.write(new byte[2 * BLOCK]);
            }
        }
        out.close();
    }

    // ------------------------------------------------------------------

    private void put(char type, String path, long size, String link) throws IOException {
        byte[] nameBytes = path.getBytes(StandardCharsets.UTF_8);
        byte[] linkBytes = link == null ? new byte[0] : link.getBytes(StandardCharsets.UTF_8);
        boolean paxName = nameBytes.length > 100;
        boolean paxLink = linkBytes.length > 100;
        if (paxName || paxLink) {
            writePax(nameBytes, paxName, linkBytes, paxLink);
        }
        byte[] h = new byte[BLOCK];
        writeField(h, 0, 100, paxName ? truncate(nameBytes, 100) : nameBytes);
        writeOctal(h, 100, 8, 0644);
        writeOctal(h, 108, 8, 0);
        writeOctal(h, 116, 8, 0);
        writeOctal(h, 124, 12, size);
        writeOctal(h, 136, 12, 0); // deterministic mtime
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        if (!paxLink && linkBytes.length > 0) {
            writeField(h, 157, 100, linkBytes);
        }
        byte[] magic = "ustar\0000".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 257, magic.length);
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        byte[] chk = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(chk, 0, h, 148, chk.length);
        out.write(h);
    }

    private void writePax(byte[] name, boolean paxName, byte[] link, boolean paxLink) throws IOException {
        StringBuilder records = new StringBuilder();
        if (paxName) {
            appendPaxRecord(records, "path", new String(name, StandardCharsets.UTF_8));
        }
        if (paxLink) {
            appendPaxRecord(records, "linkpath", new String(link, StandardCharsets.UTF_8));
        }
        byte[] data = records.toString().getBytes(StandardCharsets.UTF_8);
        put('x', "PaxHeaders.0/pax", data.length, null);
        out.write(data);
        int pad = (int) ((BLOCK - (data.length % BLOCK)) % BLOCK);
        if (pad > 0) {
            out.write(new byte[pad]);
        }
    }

    private static void appendPaxRecord(StringBuilder sb, String key, String value) {
        String kv = " " + key + "=" + value + "\n";
        int len = kv.length() + 1;
        // length field width may grow the record itself
        while (String.valueOf(len).length() + kv.length() != len) {
            len = String.valueOf(len).length() + kv.length();
        }
        sb.append(len).append(kv);
    }

    private static byte[] truncate(byte[] b, int max) {
        byte[] out = new byte[max];
        System.arraycopy(b, 0, out, 0, max);
        return out;
    }

    private static void writeField(byte[] h, int off, int len, byte[] value) {
        System.arraycopy(value, 0, h, off, Math.min(value.length, len));
    }

    private static void writeOctal(byte[] h, int off, int len, long value) {
        if (value < 0 || value >= (1L << (3 * (len - 1)))) {
            // base-256 encoding for very large values
            h[off] = (byte) 0x80;
            for (int i = off + len - 1; i > off; i--) {
                h[i] = (byte) (value & 0xff);
                value >>= 8;
            }
            return;
        }
        byte[] s = String.format("%0" + (len - 1) + "o\0", value).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, h, off, s.length);
    }
}
