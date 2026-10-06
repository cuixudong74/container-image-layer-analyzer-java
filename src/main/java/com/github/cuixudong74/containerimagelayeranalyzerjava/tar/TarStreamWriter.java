package com.github.cuixudong74.containerimagelayeranalyzerjava.tar;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 流式 tar 写入器。内存上界为 512 字节头缓冲。
 *
 * <p>输出确定性：mode/uid/gid/mtime 等元数据全部固定，
 * 相同条目序列产生逐字节一致的 tar。
 */
public class TarStreamWriter implements AutoCloseable {
    private static final int BLOCK = 512;

    private final OutputStream out;
    private final byte[] block = new byte[BLOCK];
    private long remaining;
    private long currentEntrySize;
    private boolean finished;

    public TarStreamWriter(OutputStream out) {
        this.out = out;
    }

    /** 写普通文件头，随后通过 {@link #entryStream()} 写恰好 size 字节。 */
    public OutputStream putFile(String name, long size) throws IOException {
        writeHeader(name, size, TarEntryHeader.TYPE_REGULAR, null);
        remaining = size;
        currentEntrySize = size;
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                if (remaining <= 0) {
                    throw new IOException("entry content exceeds declared size");
                }
                out.write(b);
                remaining--;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (len > remaining) {
                    throw new IOException("entry content exceeds declared size");
                }
                out.write(b, off, len);
                remaining -= len;
            }

            @Override
            public void close() throws IOException {
                finishEntry();
            }
        };
    }

    public void putDirectory(String name) throws IOException {
        writeHeader(name, 0, TarEntryHeader.TYPE_DIRECTORY, null);
    }

    public void putSymlink(String name, String target) throws IOException {
        writeHeader(name, 0, TarEntryHeader.TYPE_SYMLINK, target);
    }

    public void putHardlink(String name, String target) throws IOException {
        writeHeader(name, 0, TarEntryHeader.TYPE_HARDLINK, target);
    }

    /** 写其他特殊条目（设备节点、FIFO 等），size 固定为 0。 */
    public void putSpecial(String name, char typeFlag) throws IOException {
        writeHeader(name, 0, typeFlag, null);
    }

    /** 结束当前条目（补零填充到块对齐）。 */
    public void finishEntry() throws IOException {
        if (remaining > 0) {
            throw new IOException("entry content shorter than declared size");
        }
        writePadding(currentEntrySize);
        currentEntrySize = 0;
    }

    /** 写结束标记并 flush。 */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        byte[] zero = new byte[BLOCK * 2];
        out.write(zero);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        finish();
        out.close();
    }

    // ---- 内部 ----

    private void writeHeader(String name, long size, char type, String link) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] linkBytes = link == null ? new byte[0] : link.getBytes(StandardCharsets.UTF_8);
        if (linkBytes.length > 100) {
            writeLongRecord('K', linkBytes);
            linkBytes = truncate(linkBytes, 100);
        }
        if (nameBytes.length > 100) {
            writeLongRecord('L', nameBytes);
            nameBytes = truncate(nameBytes, 100);
        }
        Arrays.fill(block, (byte) 0);
        System.arraycopy(nameBytes, 0, block, 0, Math.min(nameBytes.length, 100));
        writeOctal(block, 100, 8, type == TarEntryHeader.TYPE_DIRECTORY ? 0755 : 0644);
        writeOctal(block, 108, 8, 0); // uid
        writeOctal(block, 116, 8, 0); // gid
        writeOctal(block, 124, 12, size);
        writeOctal(block, 136, 12, 0); // mtime 固定，保证确定性
        block[156] = (byte) type;
        System.arraycopy(linkBytes, 0, block, 157, Math.min(linkBytes.length, 100));
        byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, block, 257, magic.length);
        block[263] = '0';
        block[264] = '0';
        // checksum
        Arrays.fill(block, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte b : block) {
            sum += b & 0xff;
        }
        // 校验和字段：6 位八进制 + NUL + 空格（GNU tar 惯例）
        writeOctal(block, 148, 8, sum, 6);
        block[154] = 0;
        block[155] = (byte) ' ';
        out.write(block);
    }

    private void writeLongRecord(char type, byte[] value) throws IOException {
        // GNU longname/longlink：内容为值 + NUL
        byte[] content = Arrays.copyOf(value, value.length + 1);
        writeHeaderRaw("././@LongLink", content.length, type);
        out.write(content);
        writePadding(content.length);
    }

    private void writeHeaderRaw(String name, long size, char type) throws IOException {
        Arrays.fill(block, (byte) 0);
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nb, 0, block, 0, nb.length);
        writeOctal(block, 100, 8, 0644);
        writeOctal(block, 108, 8, 0);
        writeOctal(block, 116, 8, 0);
        writeOctal(block, 124, 12, size);
        writeOctal(block, 136, 12, 0);
        block[156] = (byte) type;
        byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, block, 257, magic.length);
        block[263] = '0';
        block[264] = '0';
        Arrays.fill(block, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte b : block) {
            sum += b & 0xff;
        }
        // 校验和字段：6 位八进制 + NUL + 空格（GNU tar 惯例）
        writeOctal(block, 148, 8, sum, 6);
        block[154] = 0;
        block[155] = (byte) ' ';
        out.write(block);
    }

    private void writePadding(long size) throws IOException {
        long rem = size % BLOCK;
        if (rem != 0) {
            byte[] pad = new byte[(int) (BLOCK - rem)];
            out.write(pad);
        }
    }

    private static byte[] truncate(byte[] b, int max) {
        return b.length <= max ? b : Arrays.copyOf(b, max);
    }

    private static void writeOctal(byte[] buf, int off, int len, long value) {
        writeOctal(buf, off, len, value, len - 1);
    }

    private static void writeOctal(byte[] buf, int off, int len, long value, int digits) {
        String s = Long.toOctalString(value);
        if (s.length() > digits) {
            s = s.substring(s.length() - digits);
        }
        int pad = digits - s.length();
        for (int i = 0; i < pad; i++) {
            buf[off + i] = '0';
        }
        for (int i = 0; i < s.length(); i++) {
            buf[off + pad + i] = (byte) s.charAt(i);
        }
        buf[off + len - 1] = 0;
    }
}
