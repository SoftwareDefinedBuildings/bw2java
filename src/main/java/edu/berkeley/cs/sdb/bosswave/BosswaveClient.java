package edu.berkeley.cs.sdb.bosswave;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class BosswaveClient implements AutoCloseable {
    private static final SimpleDateFormat Rfc3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final String hostName;
    private final int port;
    private final Thread listenerThread;

    private final Map<Integer, ResponseHandler> responseHandlers;
    private final Object responseHandlerLock = new Object();
    private final Map<Integer, MessageHandler> messageHandlers;
    private final Object resultHandlersLock = new Object();

    private Socket socket;
    private BufferedInputStream inStream;
    private BufferedOutputStream outStream;

    public BosswaveClient(String hostName, int port) {
        this.hostName = hostName;
        this.port = port;
        listenerThread = new Thread(new BWListener());
        responseHandlers = new HashMap<>();
        messageHandlers = new HashMap<>();
    }

    public void connect() throws IOException {
        socket = new Socket(hostName, port);
        inStream = new BufferedInputStream(socket.getInputStream());
        outStream = new BufferedOutputStream(socket.getOutputStream());

        // Check that we receive a well-formed acknowledgment
        try {
            Frame frame = Frame.readFromStream(inStream);
            if (frame.getCommand() != Command.HELLO) {
                close();
                throw new RuntimeException("Received invalid Bosswave ACK");
            }
        } catch (InvalidFrameException e) {
            socket.close();
            throw new RuntimeException(e);
        }

        listenerThread.start();
    }

    @Override
    public void close() throws IOException {
        inStream.close();
        outStream.close();
        socket.close();
    }

    public void setEntityFile(File f, ResponseHandler handler) throws IOException {
        BufferedInputStream stream = new BufferedInputStream(new FileInputStream(f));
        byte[] keyFile = new byte[(int) (f.length() - 1)];
        stream.read(); // Strip the first byte
        stream.read(keyFile, 0, keyFile.length);
        stream.close();

        setEntity(keyFile, handler);
    }

    private void setEntity(byte[] keyFile, ResponseHandler handler) throws IOException {
        int seqNo = Frame.generateSequenceNumber();
        Frame.Builder builder = new Frame.Builder(Command.SET_ENTITY, seqNo);
        PayloadObject.Type type = new PayloadObject.Type(new byte[]{1, 0, 1, 2});
        PayloadObject po = new PayloadObject(type, keyFile);
        builder.addPayloadObject(po);

        Frame frame = builder.build();
        frame.writeToStream(outStream);
        outStream.flush();
        installResponseHandler(seqNo, handler);
    }

    public void publish(PublishRequest request, ResponseHandler handler) throws IOException {
        Command command = Command.PUBLISH;
        if (request.isPersist()) {
            command = Command.PERSIST;
        }
        int seqNo = Frame.generateSequenceNumber();
        Frame.Builder builder = new Frame.Builder(command, seqNo);

        String uri = request.getUri();
        builder.addKVPair("uri", uri);

        if (request.isPersist()) {
            builder.setCommand(Command.PERSIST);
        } else {
            builder.setCommand(Command.PUBLISH);
        }
        builder.addKVPair("persist", Boolean.toString(request.isPersist()));

        Date expiryTime = request.getExpiry();
        if (expiryTime != null) {
            builder.addKVPair("expiry", Rfc3339.format(expiryTime));
        }

        Long expiryDelta = request.getExpiryDelta();
        if (expiryDelta != null) {
            builder.addKVPair("expiryDelta", String.format("%dms", expiryDelta));
        }

        String pac = request.getPrimaryAccessChain();
        if (pac != null) {
            builder.addKVPair("primary_access_chain", pac);
        }

        builder.addKVPair("doverify", Boolean.toString(request.doVerify()));

        ChainElaborationLevel level = request.getChainElaborationLevel();
        if (level != ChainElaborationLevel.UNSPECIFIED) {
            builder.addKVPair("elaborate_pac", level.toString().toLowerCase());
        }

        for (RoutingObject ro : request.getRoutingObjects()) {
            builder.addRoutingObject(ro);
        }
        for (PayloadObject po : request.getPayloadObjects()) {
            builder.addPayloadObject(po);
        }

        Frame f = builder.build();
        f.writeToStream(outStream);
        outStream.flush();
        installResponseHandler(seqNo, handler);
    }

    public void subscribe(SubscribeRequest request, ResponseHandler rh, MessageHandler mh) throws IOException {
        int seqNo = Frame.generateSequenceNumber();
        Frame.Builder builder = new Frame.Builder(Command.SUBSCRIBE, seqNo);

        String uri = request.getUri();
        builder.addKVPair("uri", uri);

        Date expiryTime = request.getExpiry();
        if (expiryTime != null) {
            builder.addKVPair("expiry", Rfc3339.format(expiryTime));
        }

        Long expiryDelta = request.getExpiryDelta();
        if (expiryDelta != null) {
            builder.addKVPair("expirydelta", String.format("%dms", expiryDelta));
        }

        String pac = request.getPrimaryAccessChain();
        if (pac != null) {
            builder.addKVPair("primary_access_chain", pac);
        }

        builder.addKVPair("doverify", Boolean.toString(request.doVerify()));

        ChainElaborationLevel level = request.getChainElaborationLevel();
        if (level != ChainElaborationLevel.UNSPECIFIED) {
            builder.addKVPair("elaborate_pac", level.toString().toLowerCase());
        }

        Boolean leavePacked = request.leavePacked();
        if (!leavePacked) {
            builder.addKVPair("unpack", "true");
        }

        for (RoutingObject ro : request.getRoutingObjects()) {
            builder.addRoutingObject(ro);
        }

        Frame f = builder.build();
        f.writeToStream(outStream);
        outStream.flush();

        if (rh != null) {
            installResponseHandler(seqNo, rh);
        }
        if (mh != null) {
            installMessageHandler(seqNo, mh);
        }
    }

    private void installResponseHandler(int seqNo, ResponseHandler rh) {
        synchronized (responseHandlerLock) {
            responseHandlers.put(seqNo, rh);
        }
    }

    private void installMessageHandler(int seqNo, MessageHandler mh) {
        synchronized (resultHandlersLock) {
            messageHandlers.put(seqNo, mh);
        }
    }

    private class BWListener implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    Frame frame = Frame.readFromStream(inStream);
                    int seqNo = frame.getSeqNo();

                    Command command = frame.getCommand();
                    switch (command) {
                        case RESPONSE: {
                            ResponseHandler responseHandler;
                            synchronized (responseHandlerLock) {
                                responseHandler = responseHandlers.get(seqNo);
                            }
                            if (responseHandler != null) {
                                String status = new String(frame.getFirstValue("status"), StandardCharsets.UTF_8);
                                String reason = null;
                                if (!status.equals("okay")) {
                                    reason = new String(frame.getFirstValue("reason"), StandardCharsets.UTF_8);
                                }
                                responseHandler.onResponseReceived(new Response(status, reason));
                            }
                            break;
                        }

                        case RESULT: {
                            MessageHandler messageHandler;
                            synchronized (resultHandlersLock) {
                                messageHandler = messageHandlers.get(seqNo);
                            }
                            if (messageHandler != null) {
                                String uri = new String(frame.getFirstValue("uri"), StandardCharsets.UTF_8);
                                String from = new String(frame.getFirstValue("from"), StandardCharsets.UTF_8);

                                boolean unpack = true;
                                byte[] unpackBytes = frame.getFirstValue("unpack");
                                if (unpackBytes != null) {
                                    unpack = Boolean.parseBoolean(new String(unpackBytes, StandardCharsets.UTF_8));
                                }

                                Message msg;
                                if (unpack) {
                                    msg = new Message(from, uri, frame.getRoutingObjects(), frame.getPayloadObjects());
                                } else {
                                    msg = new Message(from, uri, null, null);
                                }
                                messageHandler.onResultReceived(msg);
                            }
                            break;
                        }

                        default:
                            // Ignore frames with any other commands
                    }
                }
            } catch (InvalidFrameException e) {
                // Ignore invalid frames
            } catch (SocketException e) {
                // This should only occur when we are terminating the client and is safe to ignore
            } catch (IOException e) {
                throw new RuntimeException("Failed to read frame", e);
            }
        }
    }
}
