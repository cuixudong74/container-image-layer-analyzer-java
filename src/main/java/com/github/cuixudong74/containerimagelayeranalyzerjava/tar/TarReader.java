package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.IssueReason;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Streaming tar reader with a fixed-size buffer. Never loads a whole entry
 * (let alone a whole layer) into memory. Supports ustar, GNU longname/longlink
 * and PAX extended headers, which covers docker-save and OCI layer tars.
 *
 * <p>Malformed input is never silently accepted: truncation, checksum
 * mismatches, path traversal and illegal metadata raise
 * {@link TarFormatException} with a machine-readable {@link IssueReason}.
 */
public final class TarReader implements AutoCloseable {

    private static final int BLOCK = 512;
    /** Bound on metadata (PAX/longname) kept in memory per entry. */
    private static final int MAX_META_BYTES = 1 << 20;

    private final InputStream in;
    private final byte[] block = new byte[BLOCK];

    private long entryRemaining;
    private boolean finished;
    private boolean endOfArchiveSeen;
    private TarEntryHeader current;
    private final InputStream entryStream = new EntryStream();

    public TarReader(InputStream raw) {
        this.in = raw;
    }

    /** Advances to the next entry; returns null at the (proper) end of archive. */
    public TarEntryHeader nextEntry() throws TarFormatException {
        if (finished) {
            return null;
        }
        skipFully(entryRemaining);
        entryRemaining = 0;
        current = null;

        Map<String, String> pax = new HashMap<>();
        String gnuLongName = null;
        String gnuLongLink = null;

        // Consume metadata pseudo-entries (PAX / GNU longname) preceding the real entry.
        for (;;) {
            readBlock();
            if (endOfArchiveSeen) {
                finished = true;
                return null;
            }
            TarEntryHeader h = parseHeader(block);
            char type = h.typeFlag();
            if (type == 'x' || type == 'g') {
                byte[] data = readMetaBytes(h);
                if (type == 'x') {
                    pax.putAll(parsePax(data));
                }
                // global PAX headers carry no per-entry path info we need
                continue;
            }
            if (type == 'L') {
                gnuLongName = new String(readMetaBytes(h), StandardCharsets.UTF_8).replace("\0", "");
                continue;
            }
            if (type == 'K') {
                gnuLongLink = new String(readMetaBytes(h), StandardCharsets.UTF_8).replace("\0", "");
                continue;
            }
            // Real entry: apply overrides from PAX / GNU extensions.
            String name = pax.getOrDefault("path", gnuLongName != null ? gnuLongName : h.name());
            String link = pax.getOrDefault("linkpath", gnuLongLink != null ? gnuLongLink : h.linkName());
            long size = h.size();
            if (pax.containsKey("size")) {
                size = parsePaxSize(pax.get("size"), name);
            }
            String normalized = normalizePath(name);
            current = new TarEntryHeader(normalized, size, type, link, h.mtime());
            entryRemaining = size;
            return current;
        }
    }

    /** Streams the current entry's content; bounded to the entry's declared size. */
    public InputStream currentEntryStream() {
        if (current == null) {
            throw new IllegalStateException("no current entry");
        }
        return entryStream;
    }

    /** Discards the remainder of the current entry, if any. */
    public void skipCurrentEntry() throws TarFormatException {
        skipFully(entryRemaining);
        entryRemaining = 0;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ------------------------------------------------------------------

    private void readBlock() throws TarFormatException {
        int off = 0;
        try {
            while (off < BLOCK) {
                int n = in.read(block, off, BLOCK - off);
                if (n < 0) {
                    if (off == 0) {
                        // EOF exactly at a block boundary without end-of-archive
                        // markers: the archive was cut short.
                        throw new TarFormatException(IssueReason.TRUNCATED_ARCHIVE, null,
                                "unexpected end of stream before end-of-archive marker");
                    }
                    throw new TarFormatException(IssueReason.TRUNCATED_ARCHIVE, null,
                            "truncated header block (" + off + " of 512 bytes)");
                }
                off += n;
            }
        } catch (IOException e) {
            throw new TarFormatException(IssueReason.IO_ERROR, null, "I/O error reading header: " + e.getMessage());
        }
        boolean zero = true;
        for (byte b : block) {
            if (b != 0) {
                zero = false;
                break;
            }
        }
        endOfArchiveSeen = zero;
    }

    private TarEntryHeader parseHeader(byte[] b) throws TarFormatException {
        // Verify checksum: chksum field is treated as spaces while summing.
        long stored;
        try {
            stored = parseOctal(b, 148, 8);
        } catch (TarFormatException e) {
            throw new TarFormatException(IssueReason.INVALID_METADATA, rawName(b),
                    "malformed checksum field");
        }
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? (byte) ' ' : b[i] & 0xff;
        }
        if (sum != stored) {
            throw new TarFormatException(IssueReason.CHECKSUM_MISMATCH, rawName(b),
                    "header checksum mismatch: stored=" + stored + " computed=" + sum);
        }
        String name = cstr(b, 0, 100);
        String prefix = cstr(b, 345, 155);
        if (!prefix.isEmpty()) {
            name = prefix + "/" + name;
        }
        long size = parseSize(b, name);
        long mtime;
        try {
            mtime = parseOctal(b, 136, 12);
        } catch (TarFormatException e) {
            throw new TarFormatException(IssueReason.INVALID_METADATA, name, "malformed mtime field");
        }
        char type = (char) (b[156] & 0xff);
        String link = cstr(b, 157, 100);
        return new TarEntryHeader(name, size, type, link, mtime);
    }

    private static String rawName(byte[] b) {
        return cstr(b, 0, 100);
    }

    private static long parseSize(byte[] b, String name) throws TarFormatException {
        if ((b[124] & 0x80) != 0) {
            // base-256 (binary) size
            long v = b[124] & 0x7f;
            for (int i = 125; i < 136; i++) {
                v = (v << 8) | (b[i] & 0xff);
            }
            return v;
        }
        try {
            return parseOctal(b, 124, 12);
        } catch (TarFormatException e) {
            throw new TarFormatException(IssueReason.INVALID_METADATA, name, "malformed size field");
        }
    }

    private static long parseOctal(byte[] b, int off, int len) throws TarFormatException {
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (any) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                throw new TarFormatException(IssueReason.INVALID_METADATA, null, "non-octal digit");
            }
            v = (v << 3) | (c - '0');
            any = true;
        }
        return v;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private byte[] readMetaBytes(TarEntryHeader h) throws TarFormatException {
        if (h.size() > MAX_META_BYTES) {
            throw new TarFormatException(IssueReason.INVALID_METADATA, h.name(),
                    "metadata entry too large: " + h.size());
        }
        byte[] data = new byte[(int) h.size()];
        int off = 0;
        try {
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    throw new TarFormatException(IssueReason.TRUNCATED_ARCHIVE, h.name(),
                            "truncated metadata content");
                }
                off += n;
            }
        } catch (IOException e) {
            throw new TarFormatException(IssueReason.IO_ERROR, h.name(), "I/O error: " + e.getMessage());
        }
        long pad = padOf(h.size());
        skipRaw(pad, h.name());
        return data;
    }

    private static Map<String, String> parsePax(byte[] data) throws TarFormatException {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        while (i < data.length) {
            int sp = -1;
            for (int j = i; j < data.length && j < i + 32; j++) {
                if (data[j] == ' ') {
                    sp = j;
                    break;
                }
            }
            if (sp < 0) {
                throw new TarFormatException(IssueReason.INVALID_METADATA, null, "malformed PAX record");
            }
            int len;
            try {
                len = Integer.parseInt(new String(data, i, sp - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new TarFormatException(IssueReason.INVALID_METADATA, null, "malformed PAX length");
            }
            if (len <= 0 || i + len > data.length) {
                throw new TarFormatException(IssueReason.INVALID_METADATA, null, "PAX record out of bounds");
            }
            String rec = new String(data, sp + 1, len - (sp - i) - 1, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                out.put(rec.substring(0, eq), rec.substring(eq + 1, rec.endsWith("\n") ? rec.length() - 1 : rec.length()));
            }
            i += len;
        }
        return out;
    }

    private static long parsePaxSize(String s, String name) throws TarFormatException {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new TarFormatException(IssueReason.INVALID_METADATA, name, "malformed PAX size: " + s);
        }
    }

    /**
     * Normalizes a tar path and rejects traversal. Absolute paths and any
     * ".." segments escaping the root are refused.
     */
    static String normalizePath(String name) throws TarFormatException {
        if (name == null || name.isEmpty()) {
            throw new TarFormatException(IssueReason.INVALID_METADATA, name, "empty path");
        }
        if (name.startsWith("/") || name.startsWith("\\") || (name.length() > 1 && name.charAt(1) == ':')) {
            throw new TarFormatException(IssueReason.PATH_TRAVERSAL, name, "absolute path not allowed");
        }
        String[] parts = name.split("/");
        StringBuilder sb = new StringBuilder(name.length());
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                throw new TarFormatException(IssueReason.PATH_TRAVERSAL, name, "\"..\" segment not allowed");
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        if (sb.length() == 0) {
            // The archive root itself ("." or "./"); represented as empty path.
            return "";
        }
        return sb.toString();
    }

    private static long padOf(long size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }

    private void skipFully(long n) throws TarFormatException {
        // n is the unconsumed content remainder; when n == 0 the EntryStream has
        // already consumed the block padding, otherwise we must skip it here.
        if (n > 0) {
            skipRaw(n + padOf(n), current != null ? current.name() : null);
        }
    }

    private void onEntryContentExhausted() throws IOException {
        try {
            skipRaw(padOf(current != null ? current.size() : 0), current != null ? current.name() : null);
        } catch (TarFormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void skipRaw(long n, String path) throws TarFormatException {
        long left = n;
        try {
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new TarFormatException(IssueReason.TRUNCATED_ARCHIVE, path,
                                "truncated while skipping entry content");
                    }
                    skipped = 1;
                }
                left -= skipped;
            }
        } catch (IOException e) {
            throw new TarFormatException(IssueReason.IO_ERROR, path, "I/O error: " + e.getMessage());
        }
    }

    /** Bounded stream over the current entry's content. */
    private final class EntryStream extends InputStream {
        @Override
        public int read() throws IOException {
            if (entryRemaining <= 0) {
                return -1;
            }
            int b;
            try {
                b = in.read();
            } catch (EOFException e) {
                throw e;
            }
            if (b < 0) {
                throw new IOException("truncated entry content");
            }
            entryRemaining--;
            if (entryRemaining == 0) {
                onEntryContentExhausted();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (entryRemaining <= 0) {
                return -1;
            }
            int n = in.read(buf, off, (int) Math.min(len, entryRemaining));
            if (n < 0) {
                throw new IOException("truncated entry content");
            }
            entryRemaining -= n;
            if (entryRemaining == 0) {
                onEntryContentExhausted();
            }
            return n;
        }
    }
}
