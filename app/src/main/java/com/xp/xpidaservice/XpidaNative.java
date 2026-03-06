package com.xp.xpidaservice;

public class XpidaNative {

    static {
        System.loadLibrary("xpida_native");
    }

    public static native byte[] ping();

    public static native byte[] ps();

    public static native byte[] find(String name);

    public static native byte[] maps(int pid);

    public static native byte[] readMem(int pid, long addr, int size);

    public static final long DUMP_CHUNK_SIZE = 64L * 1024 * 1024;

    public static native byte[] dumpChunk(int pid, long start, long end);

    public static native byte[] rawCommand(String ctl, int maxBuf);
}
