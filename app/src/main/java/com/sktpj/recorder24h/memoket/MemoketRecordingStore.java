package com.sktpj.recorder24h.memoket;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

public final class MemoketRecordingStore {
    private final Context context;

    public MemoketRecordingStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public File directory() {
        File folder = new File(context.getFilesDir(), "memoket");
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create Memoket folder");
        return folder;
    }

    public synchronized void persist(String filename, byte[] data, long crc) throws Exception {
        if (!filename.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}\\.opus")) {
            throw new IllegalArgumentException("Unsafe recording name");
        }
        CRC32 checksum = new CRC32();
        checksum.update(data);
        if (checksum.getValue() != crc) throw new IllegalStateException("Checksum mismatch");
        File folder = directory();
        File raw = new File(folder, filename + ".raw");
        File playable = new File(folder, filename);
        if (raw.isFile() && playable.isFile() && raw.length() == data.length) {
            CRC32 existingCrc = new CRC32();
            byte[] existing = Files.readAllBytes(raw.toPath());
            existingCrc.update(existing);
            if (existingCrc.getValue() == crc) return;
        }
        byte[] ogg = MemoketOgg.encode(data, filename.hashCode());
        File rawPart = new File(folder, filename + ".raw.part");
        File oggPart = new File(folder, filename + ".part");
        try {
            writeDurably(rawPart, data);
            writeDurably(oggPart, ogg);
            Files.move(rawPart.toPath(), raw.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(oggPart.toPath(), playable.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            rawPart.delete();
            oggPart.delete();
        }
    }

    private static void writeDurably(File file, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(bytes);
            stream.flush();
            stream.getFD().sync();
        }
    }

    public int count() {
        File[] files = directory().listFiles((folder, name) -> name.endsWith(".opus"));
        return files == null ? 0 : files.length;
    }
}
