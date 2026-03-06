package com.xp.xpidaservice;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class XpidaTcpServer {

    private static final String TAG = "XpidaTcpServer";
    public static final int DEFAULT_PORT = 9527;

    public interface StatusListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onClientConnected(String addr);
        void onClientDisconnected(String addr);
        void onError(String message);
        void onConnectionCountChanged(int count);
    }

    private final int port;
    private final XpidaCommandExecutor executor;
    private final StatusListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    public XpidaTcpServer(int port, XpidaCommandExecutor executor, StatusListener listener) {
        this.port = port;
        this.executor = executor;
        this.listener = listener;
    }

    public void start() {
        if (running.get()) return;

        threadPool = Executors.newCachedThreadPool();
        threadPool.execute(this::serverLoop);
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        if (listener != null) {
            listener.onServerStopped();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getClientCount() {
        return clientCount.get();
    }

    public long getTotalBytesReceived() {
        return totalBytesReceived.get();
    }

    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }

    private void serverLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
            running.set(true);
            Log.i(TAG, "Server listening on port " + port);
            if (listener != null) listener.onServerStarted(port);

            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(300_000);
                    threadPool.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (running.get()) {
                        Log.e(TAG, "Accept error", e);
                        if (listener != null) listener.onError("Accept: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Server bind failed", e);
            if (listener != null) listener.onError("Bind failed: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void handleClient(Socket client) {
        String addr = client.getRemoteSocketAddress().toString();
        int count = clientCount.incrementAndGet();
        Log.i(TAG, "Client connected: " + addr + " (total: " + count + ")");
        if (listener != null) {
            listener.onClientConnected(addr);
            listener.onConnectionCountChanged(count);
        }

        try (DataInputStream in = new DataInputStream(client.getInputStream());
             DataOutputStream out = new DataOutputStream(client.getOutputStream())) {

            while (running.get() && !client.isClosed()) {
                XpidaProtocol.Request req;
                try {
                    req = XpidaProtocol.readRequest(in);
                } catch (IOException e) {
                    break;
                }

                totalBytesReceived.addAndGet(XpidaProtocol.HEADER_SIZE + req.payload.length);

                Log.d(TAG, String.format("REQ cmd=0x%02x seq=%d payload=%d bytes from %s",
                        req.cmd, req.seq, req.payload.length, addr));

                try {
                    if (req.cmd == XpidaProtocol.CMD_DUMP) {
                        processDumpStreaming(req, out);
                    } else {
                        XpidaProtocol.Response resp = processRequest(req);
                        sendResponse(out, resp);

                        Log.d(TAG, String.format("RSP status=0x%02x seq=%d payload=%d bytes to %s",
                                resp.status, resp.seq, resp.payload.length, addr));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Request processing error from " + addr, e);
                    try {
                        XpidaProtocol.writeResponse(out,
                                XpidaProtocol.makeFail(req.seq, "Server error: " + e.getMessage()));
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Client IO error: " + addr, e);
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            count = clientCount.decrementAndGet();
            Log.i(TAG, "Client disconnected: " + addr + " (total: " + count + ")");
            if (listener != null) {
                listener.onClientDisconnected(addr);
                listener.onConnectionCountChanged(count);
            }
        }
    }

    private void processDumpStreaming(XpidaProtocol.Request req, DataOutputStream out) throws IOException {
        String args = req.payloadAsString();
        String[] parts = args.trim().split("\\s+");
        if (parts.length < 3) {
            sendResponse(out, XpidaProtocol.makeFail(req.seq, "dump requires: pid hex_start hex_end"));
            return;
        }

        int pid;
        long start, end;
        try {
            pid = Integer.parseInt(parts[0]);
            start = Long.parseUnsignedLong(parts[1], 16);
            end = Long.parseUnsignedLong(parts[2], 16);
        } catch (NumberFormatException e) {
            sendResponse(out, XpidaProtocol.makeFail(req.seq, "bad dump args: " + e.getMessage()));
            return;
        }

        if (end <= start) {
            sendResponse(out, XpidaProtocol.makeFail(req.seq, "bad range: end <= start"));
            return;
        }

        long cur = start;
        long chunkSize = XpidaNative.DUMP_CHUNK_SIZE;

        while (cur < end) {
            long nxt = Math.min(cur + chunkSize, end);
            byte[] data = XpidaNative.dumpChunk(pid, cur, nxt);

            if (data == null || data.length == 0) {
                sendResponse(out, XpidaProtocol.makeFail(req.seq,
                        "dump chunk failed at 0x" + Long.toHexString(cur)));
                return;
            }

            boolean lastChunk = (nxt >= end);
            byte status = lastChunk ? XpidaProtocol.STATUS_OK : XpidaProtocol.STATUS_MORE;
            sendResponse(out, new XpidaProtocol.Response(status, req.seq, data));

            Log.d(TAG, String.format("DUMP chunk %x-%x: %d bytes, status=%s",
                    cur, nxt, data.length, lastChunk ? "OK(final)" : "MORE"));

            cur = nxt;
        }
    }

    private void sendResponse(DataOutputStream out, XpidaProtocol.Response resp) throws IOException {
        XpidaProtocol.writeResponse(out, resp);
        totalBytesSent.addAndGet(XpidaProtocol.HEADER_SIZE + resp.payload.length);
    }

    private XpidaProtocol.Response processRequest(XpidaProtocol.Request req) {
        try {
            XpidaCommandExecutor.Result result = executor.execute(req.cmd, req.payloadAsString());
            if (result.success) {
                return XpidaProtocol.makeOk(req.seq, result.data);
            } else {
                return XpidaProtocol.makeFail(req.seq, result.data);
            }
        } catch (Exception e) {
            Log.e(TAG, "Process error", e);
            return XpidaProtocol.makeFail(req.seq, "Internal error: " + e.getMessage());
        }
    }
}
