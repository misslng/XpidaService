package com.xp.xpidaservice;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class XpidaProtocol {

    public static final int MAGIC = 0x58504441; // "XPDA"
    public static final int HEADER_SIZE = 11;    // 4 + 1 + 2 + 4

    public static final byte CMD_PING = 0x01;
    public static final byte CMD_PS   = 0x02;
    public static final byte CMD_FIND = 0x03;
    public static final byte CMD_MAPS = 0x04;
    public static final byte CMD_READ = 0x05;
    public static final byte CMD_DUMP = 0x06;

    public static final byte STATUS_OK   = 0x00;
    public static final byte STATUS_FAIL = 0x01;
    public static final byte STATUS_MORE = 0x02; // chunked transfer: more chunks follow

    public static final int MAX_PAYLOAD = 128 * 1024 * 1024; // 128MB

    public static class Request {
        public byte cmd;
        public short seq;
        public byte[] payload;

        public Request(byte cmd, short seq, byte[] payload) {
            this.cmd = cmd;
            this.seq = seq;
            this.payload = payload != null ? payload : new byte[0];
        }

        public String payloadAsString() {
            return new String(payload).trim();
        }
    }

    public static class Response {
        public byte status;
        public short seq;
        public byte[] payload;

        public Response(byte status, short seq, byte[] payload) {
            this.status = status;
            this.seq = seq;
            this.payload = payload != null ? payload : new byte[0];
        }
    }

    public static Request readRequest(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Bad magic: 0x" + Integer.toHexString(magic));
        }
        byte cmd = in.readByte();
        short seq = in.readShort();
        int payloadLen = in.readInt();
        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
            throw new IOException("Bad payload length: " + payloadLen);
        }
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            in.readFully(payload);
        }
        return new Request(cmd, seq, payload);
    }

    public static void writeResponse(DataOutputStream out, Response resp) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC);
        header.put(resp.status);
        header.putShort(resp.seq);
        header.putInt(resp.payload.length);
        out.write(header.array());
        if (resp.payload.length > 0) {
            out.write(resp.payload);
        }
        out.flush();
    }

    public static Response makeOk(short seq, byte[] data) {
        return new Response(STATUS_OK, seq, data);
    }

    public static Response makeOk(short seq, String text) {
        return new Response(STATUS_OK, seq, text.getBytes());
    }

    public static Response makeFail(short seq, byte[] data) {
        return new Response(STATUS_FAIL, seq, data);
    }

    public static Response makeFail(short seq, String errorMsg) {
        return new Response(STATUS_FAIL, seq, errorMsg.getBytes());
    }
}
