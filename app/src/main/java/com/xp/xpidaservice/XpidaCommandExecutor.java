package com.xp.xpidaservice;

import android.util.Log;

public class XpidaCommandExecutor {

    private static final String TAG = "XpidaExecutor";

    public static class Result {
        public final boolean success;
        public final byte[] data;
        public final boolean isBinary;

        public Result(boolean success, byte[] data, boolean isBinary) {
            this.success = success;
            this.data = data;
            this.isBinary = isBinary;
        }
    }

    public Result execute(byte cmd, String args) {
        try {
            switch (cmd) {
                case XpidaProtocol.CMD_PING:
                    return byteResult(XpidaNative.ping());
                case XpidaProtocol.CMD_PS:
                    return byteResult(XpidaNative.ps());
                case XpidaProtocol.CMD_FIND:
                    return byteResult(XpidaNative.find(args));
                case XpidaProtocol.CMD_MAPS: {
                    int pid = parseFirstInt(args);
                    return byteResult(XpidaNative.maps(pid));
                }
                case XpidaProtocol.CMD_READ: {
                    String[] parts = args.trim().split("\\s+");
                    if (parts.length < 3) {
                        return new Result(false, "read requires: pid hex_addr size".getBytes(), false);
                    }
                    int pid = Integer.parseInt(parts[0]);
                    long addr = Long.parseUnsignedLong(parts[1], 16);
                    int size = Integer.parseInt(parts[2]);
                    byte[] data = XpidaNative.readMem(pid, addr, size);
                    if (data != null) {
                        return new Result(true, data, true);
                    }
                    return new Result(false, "read returned null".getBytes(), false);
                }
                case XpidaProtocol.CMD_DUMP:
                    return new Result(false, "dump must be handled via streaming".getBytes(), false);
                default:
                    return new Result(false, ("Unknown cmd: 0x" + Integer.toHexString(cmd & 0xFF)).getBytes(), false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Execute failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Result(false, msg.getBytes(), false);
        }
    }

    private Result byteResult(byte[] data) {
        if (data == null || data.length == 0) {
            return new Result(false, "native returned null".getBytes(), false);
        }
        String preview = new String(data, 0, Math.min(data.length, 6));
        if (preview.startsWith("ERROR:")) {
            return new Result(false, data, false);
        }
        return new Result(true, data, false);
    }

    private int parseFirstInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        return Integer.parseInt(s.trim().split("\\s+")[0]);
    }
}
