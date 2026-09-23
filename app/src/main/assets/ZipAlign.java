import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 4-byte zip alignment, with optional page alignment for uncompressed .so files. */
public final class ZipAlign {
    private ZipAlign() {}

    public static void main(String[] args) throws Exception {
        int align = 4;
        int page = 0;
        boolean force = false;
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-f".equals(arg)) {
                force = true;
            } else if ("-p".equals(arg)) {
                if (page == 0) page = 4096;
            } else if ("-P".equals(arg)) {
                page = Integer.parseInt(args[++i]);
            } else if ("-v".equals(arg) || "-c".equals(arg)) {
                // accepted, ignored
            } else if (arg.startsWith("-")) {
                System.err.println("Unsupported zipalign option " + arg);
                System.exit(2);
            } else {
                rest.add(arg);
            }
        }
        if (rest.size() == 3) {
            align = Integer.parseInt(rest.get(0));
            rest.remove(0);
        }
        if (rest.size() != 2 || align <= 0) {
            System.err.println("Usage: zipalign [-f] [-p] [-P pagesize] align infile outfile");
            System.exit(2);
        }
        Path input = Path.of(rest.get(0));
        Path output = Path.of(rest.get(1));
        if (Files.exists(output) && !force) {
            System.err.println("Output exists: " + output);
            System.exit(1);
        }
        Path temp = output.resolveSibling(output.getFileName().toString() + ".aligning");
        align(input, temp, align, page);
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
    }

    static void align(Path input, Path output, int align, int page) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(input.toFile(), "r");
             RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw")) {
            out.setLength(0);
            ByteBuffer eocd = readEocd(in);
            int count = Short.toUnsignedInt(eocd.getShort(10));
            int cdSize = eocd.getInt(12);
            int cdOffset = eocd.getInt(16);
            byte[] comment = new byte[Short.toUnsignedInt(eocd.getShort(20))];
            in.seek(in.length() - comment.length);
            in.readFully(comment);

            in.seek(cdOffset);
            byte[] central = new byte[cdSize];
            in.readFully(central);
            List<Entry> entries = parseCentral(central, count);

            List<Integer> newOffsets = new ArrayList<Integer>();
            for (Entry entry : entries) {
                byte[] local = readLocal(in, entry.offset);
                int nameLen = Short.toUnsignedInt(getShort(local, 26));
                int extraLen = Short.toUnsignedInt(getShort(local, 28));
                int method = Short.toUnsignedInt(getShort(local, 8));
                int flags = Short.toUnsignedInt(getShort(local, 6));
                boolean stored = method == 0;
                int target = stored && entry.name.endsWith(".so") && page > 0 ? page : align;
                int padding = 0;
                if (stored) {
                    long dataStart = out.getFilePointer() + 30L + nameLen + extraLen;
                    padding = (int) ((target - (dataStart % target)) % target);
                }
                newOffsets.add((int) out.getFilePointer());
                byte[] rewritten = local.clone();
                putShort(rewritten, 28, extraLen + padding);
                out.write(rewritten, 0, 30);
                out.write(local, 30, nameLen + extraLen);
                if (padding > 0) out.write(new byte[padding]);
                int compSize = getInt(local, 18);
                if ((flags & 8) != 0) {
                    copy(in, out, compSize);
                    copyDescriptor(in, out);
                } else {
                    copy(in, out, compSize);
                }
            }

            int newCd = (int) out.getFilePointer();
            int written = writeCentral(out, central, newOffsets);
            writeEocd(out, count, written, newCd, comment);
        }
    }

    private static byte[] readLocal(RandomAccessFile in, int offset) throws IOException {
        in.seek(offset);
        byte[] head = new byte[30];
        in.readFully(head);
        if (getInt(head, 0) != 0x04034b50) throw new IOException("Bad local header");
        int nameLen = Short.toUnsignedInt(getShort(head, 26));
        int extraLen = Short.toUnsignedInt(getShort(head, 28));
        byte[] full = new byte[30 + nameLen + extraLen];
        System.arraycopy(head, 0, full, 0, 30);
        in.readFully(full, 30, nameLen + extraLen);
        return full;
    }

    private static void copyDescriptor(RandomAccessFile in, RandomAccessFile out) throws IOException {
        byte[] maybe = new byte[16];
        in.readFully(maybe);
        if (getInt(maybe, 0) == 0x08074b50) {
            out.write(maybe);
        } else {
            out.write(maybe, 0, 12);
            in.seek(in.getFilePointer() - 4);
        }
    }

    private static void copy(RandomAccessFile in, RandomAccessFile out, int size) throws IOException {
        byte[] buffer = new byte[8192];
        int left = size;
        while (left > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, left));
            if (read < 0) throw new EOFException("Truncated zip data");
            out.write(buffer, 0, read);
            left -= read;
        }
    }

    private static List<Entry> parseCentral(byte[] central, int count) throws IOException {
        List<Entry> entries = new ArrayList<Entry>();
        int pos = 0;
        for (int i = 0; i < count; i++) {
            if (getInt(central, pos) != 0x02014b50) throw new IOException("Bad central header");
            int nameLen = Short.toUnsignedInt(getShort(central, pos + 28));
            int extraLen = Short.toUnsignedInt(getShort(central, pos + 30));
            int commentLen = Short.toUnsignedInt(getShort(central, pos + 32));
            int offset = getInt(central, pos + 42);
            String name = new String(central, pos + 46, nameLen, java.nio.charset.StandardCharsets.UTF_8);
            entries.add(new Entry(name, offset, 46 + nameLen + extraLen + commentLen, pos));
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return entries;
    }

    private static int writeCentral(RandomAccessFile out, byte[] central, List<Integer> offsets) throws IOException {
        int pos = 0;
        int start = (int) out.getFilePointer();
        for (int i = 0; i < offsets.size(); i++) {
            int nameLen = Short.toUnsignedInt(getShort(central, pos + 28));
            int extraLen = Short.toUnsignedInt(getShort(central, pos + 30));
            int commentLen = Short.toUnsignedInt(getShort(central, pos + 32));
            int length = 46 + nameLen + extraLen + commentLen;
            byte[] entry = new byte[length];
            System.arraycopy(central, pos, entry, 0, length);
            putInt(entry, 42, offsets.get(i));
            out.write(entry);
            pos += length;
        }
        return (int) out.getFilePointer() - start;
    }

    private static void writeEocd(RandomAccessFile out, int count, int cdSize, int cdOffset, byte[] comment) throws IOException {
        byte[] eocd = new byte[22 + comment.length];
        ByteBuffer buffer = ByteBuffer.wrap(eocd).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) count);
        buffer.putShort((short) count);
        buffer.putInt(cdSize);
        buffer.putInt(cdOffset);
        buffer.putShort((short) comment.length);
        System.arraycopy(comment, 0, eocd, 22, comment.length);
        out.write(eocd);
    }

    private static ByteBuffer readEocd(RandomAccessFile in) throws IOException {
        long length = in.length();
        int window = (int) Math.min(length, 65557);
        byte[] tail = new byte[window];
        in.seek(length - window);
        in.readFully(tail);
        for (int i = tail.length - 22; i >= 0; i--) {
            if (getInt(tail, i) == 0x06054b50) {
                ByteBuffer buffer = ByteBuffer.wrap(tail, i, 22).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer copy = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
                copy.put(buffer);
                copy.flip();
                int cdOffset = copy.getInt(16);
                if (cdOffset == 0xffffffff) throw new IOException("ZIP64 archives are not supported.");
                return copy;
            }
        }
        throw new IOException("Zip end record not found.");
    }

    private static int getInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static short getShort(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    private static void putInt(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private static void putShort(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value);
    }

    private static final class Entry {
        final String name;
        final int offset;
        final int length;
        final int position;

        Entry(String name, int offset, int length, int position) {
            this.name = name;
            this.offset = offset;
            this.length = length;
            this.position = position;
        }
    }
}
