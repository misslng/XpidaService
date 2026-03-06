package com.xp.xpidaservice;

public class XpidaNative {

    static {
        System.loadLibrary("xpida_native");
    }

    public static native String ping();

    public static native String ps();

    public static native String find(String name);

    public static native String maps(int pid);

    /**
     * @param addr virtual address (unsigned 64-bit, passed as long)
     * @param size bytes to read
     * @return raw memory bytes, or null on failure
     */
    public static native byte[] readMem(int pid, long addr, int size);

    public static final long DUMP_CHUNK_SIZE = 64L * 1024 * 1024; // 64MB

    /**
     * Single dump syscall, range clamped to 64MB max.
     * Call in a loop for larger ranges.
     * @return raw memory bytes for this chunk, or null on failure
     */
    public static native byte[] dumpChunk(int pid, long start, long end);

    public static native String rawTextCommand(String ctl);

    public static native byte[] rawBinaryCommand(String ctl);
}
