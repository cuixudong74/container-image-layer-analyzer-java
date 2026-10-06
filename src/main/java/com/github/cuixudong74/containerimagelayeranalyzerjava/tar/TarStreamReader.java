package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

import com.github.cuixudong74.containerimagelayeranalyzerjava.model.ErrorReason;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 流式 tar 读取器。
 *
 * <p>内存占用上界 = 512 字节头缓冲 + 调用方传入的读缓冲，不缓存任何条目内容。
 * 支持 ustar/pax/GNU longname；对截断、校验和错误、路径穿越、非法元数据
 * 分别抛出携带 {@link ErrorReason} 的 {@link TarParseException}。
 *
 * <p>使用方式：循环 {@link #nextEntry()}，对每个条目通过 {@link #entryStream()}
 * 读取恰好 size 字节，或调用 {@link #skipEntry()} 跳过；读取下一个条目前
 * 当前条目必须被消费或跳过。
 */
public class TarStreamReader implements AutoCloseable {
    private static final int BLOCK = 512;

    private final InputStream in;
    private final byte[] block = new byte[BLOCK];

    private TarEntryHeader current;
    private long entryRemaining;
    private long padRemaining;
    private boolean eof;
    /** GNU/pax 长名与长链接目标，作用于下一个条目。 */
    private String pendingLongName;
    private String pendingLongLink;

    public TarStreamReader(InputStream in, int bufferSize) {
        this.in = in;
    }

    /**
     * 读取下一个条目头；流结束返回 null。
     */
    public TarEntryHeader nextEntry() throws IOException, TarParseException {
        skipEntry();
        if (eof) {
            return null;
        }
        while (true) {
            readBlockOrEof();
            if (eof) {
                return null;
            }
            if (isZeroBlock(block)) {
                // 结束标记：允许后续还有零块或截断的填充
                eof = true;
                return null;
            }
            verifyChecksum(block);
            TarEntryHeader h = parseHeader(block);
            if (h.typeFlag() == 'L') { // GNU longname
                pendingLongName = readLongValue(h.size());
                continue;
            }
            if (h.typeFlag() == 'K') { // GNU longlink
                pendingLongLink = readLongValue(h.size());
                continue;
            }
            if (h.typeFlag() == 'x') { // pax extended header
                parsePax(h.size());
                continue;
            }
            if (h.typeFlag() == 'g') { // pax global header：跳过
                skipEntry();
                continue;
            }
            String name = pendingLongName != null ? pendingLongName : h.name();
            String link = pendingLongLink != null ? pendingLongLink : h.linkName();
            pendingLongName = null;
            pendingLongLink = null;
            validateName(name);
            if (link != null) {
                validateLinkName(link);
            }
            current = new TarEntryHeader(name, h.size(), h.typeFlag(), link);
            entryRemaining = h.size();
            padRemaining = padding(h.size());
            return current;
        }
    }

    /** 当前条目内容流（恰好 size 字节，超出返回 -1）。 */
    public InputStream entryStream() {
        if (current == null) {
            throw new IllegalStateException("no current entry");
        }
        return new InputStream() {
            @Override
            public int read() throws IOException {
                if (entryRemaining <= 0) {
                    return -1;
                }
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("truncated tar entry: " + current.name());
                }
                entryRemaining--;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (entryRemaining <= 0) {
                    return -1;
                }
                int n = in.read(b, off, (int) Math.min(len, entryRemaining));
                if (n < 0) {
                    throw new EOFException("truncated tar entry: " + current.name());
                }
                entryRemaining -= n;
                return n;
            }
        };
    }

    /** 跳过当前条目未读内容与填充。 */
    public void skipEntry() throws IOException {
        if (current == null) {
            return;
        }
        long toSkip = entryRemaining + padRemaining;
        entryRemaining = 0;
        padRemaining = 0;
        current = null;
        while (toSkip > 0) {
            long n = in.skip(toSkip);
            if (n <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("truncated tar while skipping entry");
                }
                n = 1;
            }
            toSkip -= n;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ---- 内部实现 ----

    private void readBlockOrEof() throws IOException, TarParseException {
        int off = 0;
        while (off < BLOCK) {
            int n = in.read(block, off, BLOCK - off);
            if (n < 0) {
                if (off == 0) {
                    // 未见到零块结束标记就到流尾：视为截断，不静默通过
                    throw new TarParseException(ErrorReason.TRUNCATED_ARCHIVE,
                            "tar stream ended without end marker");
                }
                throw new TarParseException(ErrorReason.TRUNCATED_ARCHIVE,
                        "truncated tar header block");
            }
            off += n;
        }
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte v : b) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    private static void verifyChecksum(byte[] b) throws TarParseException {
        long stored;
        try {
            stored = parseOctal(b, 148, 8);
        } catch (TarParseException e) {
            throw new TarParseException(ErrorReason.INVALID_METADATA,
                    "invalid header checksum field");
        }
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (b[i] & 0xff);
        }
        if (sum != stored) {
            throw new TarParseException(ErrorReason.CHECKSUM_MISMATCH,
                    "tar header checksum mismatch: stored=" + stored + " computed=" + sum);
        }
    }

    private static TarEntryHeader parseHeader(byte[] b) throws TarParseException {
        String name = readString(b, 0, 100);
        long size = parseSize(b, 124, 12);
        char type = (char) (b[156] & 0xff);
        String link = readString(b, 157, 100);
        String magic = readString(b, 257, 6);
        String prefix = "";
        if ("ustar".equals(magic)) {
            prefix = readString(b, 345, 155);
        }
        String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
        return new TarEntryHeader(fullName, size, type, link.isEmpty() ? null : link);
    }

    private static long parseSize(byte[] b, int off, int len) throws TarParseException {
        if ((b[off] & 0x80) != 0) { // base-256 二进制大端
            long v = 0;
            for (int i = off + 1; i < off + len; i++) {
                v = (v << 8) | (b[i] & 0xff);
            }
            return v;
        }
        return parseOctal(b, off, len);
    }

    private static long parseOctal(byte[] b, int off, int len) throws TarParseException {
        long v = 0;
        boolean seen = false;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c == ' ' || c == 0) {
                if (seen) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                throw new TarParseException(ErrorReason.INVALID_METADATA,
                        "invalid octal field at offset " + off);
            }
            v = (v << 3) | (c - '0');
            seen = true;
        }
        return v;
    }

    private static String readString(byte[] b, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long padding(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? 0 : BLOCK - rem;
    }

    private String readLongValue(long size) throws IOException, TarParseException {
        if (size <= 0 || size > (1 << 20)) {
            throw new TarParseException(ErrorReason.INVALID_METADATA,
                    "unreasonable longname size: " + size);
        }
        byte[] buf = new byte[(int) size];
        int off = 0;
        while (off < size) {
            int n = in.read(buf, off, (int) size - off);
            if (n < 0) {
                throw new TarParseException(ErrorReason.TRUNCATED_ARCHIVE,
                        "truncated longname entry");
            }
            off += n;
        }
        long pad = padding(size);
        while (pad > 0) {
            long n = in.skip(pad);
            if (n <= 0) {
                if (in.read() < 0) {
                    throw new TarParseException(ErrorReason.TRUNCATED_ARCHIVE,
                            "truncated longname padding");
                }
                n = 1;
            }
            pad -= n;
        }
        int len = buf.length;
        while (len > 0 && buf[len - 1] == 0) {
            len--;
        }
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private void parsePax(long size) throws IOException, TarParseException {
        if (size <= 0 || size > (1 << 20)) {
            skipEntry();
            return;
        }
        byte[] buf = new byte[(int) size];
        int off = 0;
        while (off < size) {
            int n = in.read(buf, off, (int) size - off);
            if (n < 0) {
                throw new TarParseException(ErrorReason.TRUNCATED_ARCHIVE,
                        "truncated pax header");
            }
            off += n;
        }
        long pad = padding(size);
        while (pad > 0) {
            long n = in.skip(pad);
            if (n <= 0) {
                if (in.read() < 0) {
                    throw new TarParseException(ErrorReason.TRUNCATED_ARCHIVE,
                            "truncated pax padding");
                }
                n = 1;
            }
            pad -= n;
        }
        // 解析 "len key=value\n" 记录，只关心 path 与 linkpath
        int pos = 0;
        while (pos < buf.length) {
            int sp = pos;
            while (sp < buf.length && buf[sp] != ' ') {
                sp++;
            }
            if (sp >= buf.length) {
                break;
            }
            int recLen;
            try {
                recLen = Integer.parseInt(new String(buf, pos, sp - pos, StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                break;
            }
            if (recLen <= 0 || pos + recLen > buf.length) {
                break;
            }
            String rec = new String(buf, sp + 1, recLen - (sp - pos) - 1, StandardCharsets.UTF_8);
            if (rec.endsWith("\n")) {
                rec = rec.substring(0, rec.length() - 1);
            }
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String key = rec.substring(0, eq);
                String val = rec.substring(eq + 1);
                if ("path".equals(key)) {
                    pendingLongName = val;
                } else if ("linkpath".equals(key)) {
                    pendingLongLink = val;
                }
            }
            pos += recLen;
        }
    }

    private static void validateName(String name) throws TarParseException {
        if (name == null || name.isEmpty()) {
            throw new TarParseException(ErrorReason.INVALID_METADATA, "empty entry name");
        }
        if (name.indexOf('\0') >= 0) {
            throw new TarParseException(ErrorReason.INVALID_METADATA, "NUL in entry name");
        }
        // 前导 / 视为镜像根，由上层归一化；驱动器号绝对路径与 .. 一律拒绝
        if (name.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new TarParseException(ErrorReason.PATH_TRAVERSAL,
                    "absolute path entry: " + name);
        }
        for (String seg : name.split("/")) {
            if (seg.equals("..")) {
                throw new TarParseException(ErrorReason.PATH_TRAVERSAL,
                        "path traversal entry: " + name);
            }
        }
    }

    private static void validateLinkName(String link) throws TarParseException {
        if (link.indexOf('\0') >= 0) {
            throw new TarParseException(ErrorReason.INVALID_METADATA, "NUL in link target");
        }
        if (link.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new TarParseException(ErrorReason.PATH_TRAVERSAL,
                    "absolute link target: " + link);
        }
        for (String seg : link.split("/")) {
            if (seg.equals("..")) {
                throw new TarParseException(ErrorReason.PATH_TRAVERSAL,
                        "link target escapes root: " + link);
            }
        }
    }
}
