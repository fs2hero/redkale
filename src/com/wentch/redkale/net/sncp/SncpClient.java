/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.wentch.redkale.net.sncp;

import com.wentch.redkale.convert.bson.*;
import com.wentch.redkale.net.*;
import static com.wentch.redkale.net.sncp.SncpRequest.*;
import com.wentch.redkale.util.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

/**
 *
 * @author zhangjx
 */
public final class SncpClient {

    protected static final class SncpAction {

        protected final DLong actionid;

        protected final Method method;

        protected final Type resultTypes;  //void 必须设为 null

        protected final Type[] paramTypes;

        protected final int addressParamIndex;

        public SncpAction(Method method, DLong actionid) {
            this.actionid = actionid;
            Type rt = method.getGenericReturnType();
            if (rt instanceof TypeVariable) {
                TypeVariable tv = (TypeVariable) rt;
                if (tv.getBounds().length == 1) rt = tv.getBounds()[0];
            }
            this.resultTypes = rt == void.class ? null : rt;
            this.paramTypes = method.getGenericParameterTypes();
            this.method = method;
            Annotation[][] anns = method.getParameterAnnotations();
            int addrIndex = -1;
            if (anns.length > 0) {
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < anns.length; i++) {
                    if (anns[i].length > 0) {
                        for (Annotation ann : anns[i]) {
                            if (ann.annotationType() == SncpParameter.class && SocketAddress.class.isAssignableFrom(params[i])) {
                                addrIndex = i;
                                break;
                            }
                        }
                    }
                }
            }
            this.addressParamIndex = addrIndex;
        }

        @Override
        public String toString() {
            return "{" + actionid + "," + (method == null ? "null" : method.getName()) + "}";
        }
    }

    private final Logger logger = Logger.getLogger(SncpClient.class.getSimpleName());

    private final boolean debug = logger.isLoggable(Level.FINEST);

    protected final String name;

    protected final boolean remote;

    private final Class serviceClass;

    protected final InetSocketAddress address;

    protected final HashSet<String> groups;

    private final byte[] addrBytes;

    private final int addrPort;

    protected final long nameid;

    protected final long serviceid;

    protected final SncpAction[] actions;

    protected final Consumer<Runnable> executor;

    public SncpClient(final String serviceName, final Consumer<Runnable> executor, final long serviceid0, boolean remote, final Class serviceClass,
            boolean onlySncpDyn, final InetSocketAddress clientAddress, final HashSet<String> groups) {
        if (serviceName.length() > 10) throw new RuntimeException(serviceClass + " @Resource name(" + serviceName + ") too long , must less 11");
        this.remote = remote;
        this.executor = executor;
        this.serviceClass = serviceClass;
        this.address = clientAddress;
        this.groups = groups;
        //if (subLocalClass != null && !serviceClass.isAssignableFrom(subLocalClass)) throw new RuntimeException(subLocalClass + " is not " + serviceClass + " sub class ");
        this.name = serviceName;
        this.nameid = Sncp.hash(serviceName);
        this.serviceid = serviceid0 > 0 ? serviceid0 : Sncp.hash(serviceClass);
        final List<SncpAction> methodens = new ArrayList<>();
        //------------------------------------------------------------------------------
        for (java.lang.reflect.Method method : parseMethod(serviceClass, onlySncpDyn)) {
            SncpAction en = new SncpAction(method, Sncp.hash(method));
            methodens.add(en);
        }
        this.actions = methodens.toArray(new SncpAction[methodens.size()]);
        this.addrBytes = clientAddress == null ? new byte[4] : clientAddress.getAddress().getAddress();
        this.addrPort = clientAddress == null ? 0 : clientAddress.getPort();
    }

    public long getNameid() {
        return nameid;
    }

    public long getServiceid() {
        return serviceid;
    }

    public int getActionCount() {
        return actions.length;
    }

    @Override
    public String toString() {
        String service = serviceClass.getName();
        if (remote) service = service.replace(Sncp.LOCALPREFIX, Sncp.REMOTEPREFIX);
        return this.getClass().getSimpleName() + "(service = " + service + ", serviceid = " + serviceid
                + ", name = " + name + ", nameid = " + nameid + ", address = " + (address == null ? "" : (address.getHostString() + ":" + address.getPort()))
                + ", groups = " + groups + ", actions.size = " + actions.length + ")";
    }

    public static List<Method> parseMethod(final Class serviceClass, boolean onlySncpDyn) {
        final List<Method> list = new ArrayList<>();
        final List<Method> multis = new ArrayList<>();
        final Map<DLong, Method> actionids = new HashMap<>();

        for (final java.lang.reflect.Method method : serviceClass.getMethods()) {
            if (method.isSynthetic()) continue;
            final int mod = method.getModifiers();
            if (Modifier.isStatic(mod)) continue;
            if (Modifier.isFinal(mod)) continue;
            if (method.getName().equals("getClass") || method.getName().equals("toString")) continue;
            if (method.getName().equals("equals") || method.getName().equals("hashCode")) continue;
            if (method.getName().equals("notify") || method.getName().equals("notifyAll") || method.getName().equals("wait")) continue;
            if (method.getName().equals("init") || method.getName().equals("destroy") || method.getName().equals("name")) continue;
            if (onlySncpDyn && method.getAnnotation(SncpDyn.class) == null) continue;
            DLong actionid = Sncp.hash(method);
            Method old = actionids.get(actionid);
            if (old != null) {
                if (old.getDeclaringClass().equals(method.getDeclaringClass()))
                    throw new RuntimeException(serviceClass.getName() + " have one more same action(Method=" + method + ", actionid=" + actionid + ")");
                continue;
            }
            actionids.put(actionid, method);
            if (method.getAnnotation(SncpDyn.class) != null) {
                multis.add(method);
            } else {
                list.add(method);
            }
        }
        list.addAll(multis);
        if (onlySncpDyn && list.size() > 1) {
            list.sort((m1, m2) -> m1.getAnnotation(SncpDyn.class).index() - m2.getAnnotation(SncpDyn.class).index());
        }
        return list;
    }

    public <T> T remote(final BsonConvert convert, Transport transport, final int index, final Object... params) {
        Future<byte[]> future = transport.isTCP() ? remoteTCP(convert, transport, actions[index], params) : remoteUDP(convert, transport, actions[index], params);
        try {
            return convert.convertFrom(actions[index].resultTypes, future.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(actions[index].method + " sncp remote error", e);
        }
    }

    public <T> void remote(final BsonConvert convert, Transport[] transports, boolean run, final int index, final Object... params) {
        if (!run) return;
        for (Transport transport : transports) {
            if (transport.isTCP()) {
                remoteTCP(convert, transport, actions[index], params);
            } else {
                remoteUDP(convert, transport, actions[index], params);
            }
        }
    }

    public <T> void asyncRemote(final BsonConvert convert, Transport[] transports, boolean run, final int index, final Object... params) {
        if (!run) return;
        if (executor != null) {
            executor.accept(() -> {
                for (Transport transport : transports) {
                    if (transport.isTCP()) {
                        remoteTCP(convert, transport, actions[index], params);
                    } else {
                        remoteUDP(convert, transport, actions[index], params);
                    }
                }
            });
        } else {
            for (Transport transport : transports) {
                if (transport.isTCP()) {
                    remoteTCP(convert, transport, actions[index], params);
                } else {
                    remoteUDP(convert, transport, actions[index], params);
                }
            }
        }
    }

    private Future<byte[]> remoteUDP(final BsonConvert convert, final Transport transport, final SncpAction action, final Object... params) {
        Type[] myparamtypes = action.paramTypes;
        final Supplier<ByteBuffer> supplier = transport.getBufferSupplier();
        final BsonWriter bw = convert.pollBsonWriter(() -> supplier.get().put(DEFAULT_HEADER)); // 将head写入
        for (int i = 0; i < params.length; i++) {
            convert.convertTo(bw, myparamtypes[i], params[i]);
        }
        final SocketAddress addr = action.addressParamIndex >= 0 ? (SocketAddress) params[action.addressParamIndex] : null;
        final AsyncConnection conn = transport.pollConnection(addr);
        if (conn == null || !conn.isOpen()) throw new RuntimeException("sncp " + (conn == null ? addr : conn.getRemoteAddress()) + " cannot connect");

        final int reqBodyLength = bw.count(); //body总长度
        final long seqid = System.nanoTime();
        final DLong actionid = action.actionid;
        final int readto = conn.getReadTimeoutSecond();
        final int writeto = conn.getWriteTimeoutSecond();
        final ByteBuffer buffer = transport.pollBuffer();
        try {
            //------------------------------ 发送请求 ---------------------------------------------------
            int pos = 0;
            for (ByteBuffer buf : bw.toBuffers()) {
                int len = buf.remaining() - HEADER_SIZE;
                fillHeader(buf, seqid, actionid, reqBodyLength, pos, len);
                pos += len;
                Thread.sleep(20);
                conn.write(buf).get(writeto > 0 ? writeto : 3, TimeUnit.SECONDS);
                transport.offerBuffer(buf);
            }
            //------------------------------ 接收响应 ---------------------------------------------------
            int received = 0;
            int respBodyLength = 1;
            byte[] respBody = null;
            while (received < respBodyLength) {
                buffer.clear();
                conn.read(buffer).get(readto > 0 ? readto : 3, TimeUnit.SECONDS);
                buffer.flip();

                checkResult(seqid, action, buffer);
                int respbodylen = buffer.getInt();
                if (respBody == null) {
                    respBodyLength = respbodylen;
                    respBody = new byte[respBodyLength];
                }
                int bodyOffset = buffer.getInt();  // 
                int frameLength = buffer.getInt();  // 
                final int retcode = buffer.getInt();
                if (retcode != 0) throw new RuntimeException("remote service(" + action.method + ") deal error (retcode=" + retcode + ", retinfo=" + SncpResponse.getRetCodeInfo(retcode) + ")");
                int len = Math.min(buffer.remaining(), frameLength);
                buffer.get(respBody, bodyOffset, len);
                received += len;
            }
            return new SncpFuture<>(respBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            transport.offerBuffer(buffer);
            transport.offerConnection(conn);
        }
    }

    private Future<byte[]> remoteTCP(final BsonConvert convert, final Transport transport, final SncpAction action, final Object... params) {
        Type[] myparamtypes = action.paramTypes;
        final BsonWriter writer = convert.pollBsonWriter(transport.getBufferSupplier()); // 将head写入
        writer.writeTo(DEFAULT_HEADER);
        for (int i = 0; i < params.length; i++) {
            convert.convertTo(writer, myparamtypes[i], params[i]);
        }
        final int reqBodyLength = writer.count() - HEADER_SIZE; //body总长度
        final long seqid = System.nanoTime();
        final DLong actionid = action.actionid;
        final SocketAddress addr = action.addressParamIndex >= 0 ? (SocketAddress) params[action.addressParamIndex] : null;
        final AsyncConnection conn = transport.pollConnection(addr);
        if (conn == null || !conn.isOpen()) throw new RuntimeException("sncp " + (conn == null ? addr : conn.getRemoteAddress()) + " cannot connect");
        final ByteBuffer[] sendBuffers = writer.toBuffers();
        fillHeader(sendBuffers[0], seqid, actionid, reqBodyLength, 0, reqBodyLength);

        final ByteBuffer buffer = transport.pollBuffer();
        final SncpFuture<byte[]> future = new SncpFuture();
        conn.write(sendBuffers, sendBuffers, new CompletionHandler<Integer, ByteBuffer[]>() {

            @Override
            public void completed(Integer result, ByteBuffer[] attachments) {
                int index = -1;
                for (int i = 0; i < attachments.length; i++) {
                    if (attachments[i].hasRemaining()) {
                        index = i;
                        break;
                    } else {
                        transport.offerBuffer(attachments[i]);
                    }
                }
                if (index == 0) {
                    conn.write(attachments, attachments, this);
                    return;
                } else if (index > 0) {
                    ByteBuffer[] newattachs = new ByteBuffer[attachments.length - index];
                    System.arraycopy(attachments, index, newattachs, 0, newattachs.length);
                    conn.write(newattachs, newattachs, this);
                    return;
                }
                //----------------------- 读取返回结果 -------------------------------------
                buffer.clear();
                conn.read(buffer, null, new CompletionHandler<Integer, Void>() {

                    private byte[] body;

                    private int received;

                    @Override
                    public void completed(Integer count, Void attachment2) {
                        if (count < 1 && buffer.remaining() == buffer.limit()) {   //没有数据可读
                            future.set(new RuntimeException(action.method + " sncp remote no response data"));
                            transport.offerBuffer(buffer);
                            transport.offerConnection(conn);
                            return;
                        }
                        if (received < 1 && buffer.limit() < buffer.remaining() + HEADER_SIZE) { //header都没读全
                            conn.read(buffer, attachment2, this);
                            return;
                        }
                        buffer.flip();
                        if (received > 0) {
                            int offset = this.received;
                            this.received += buffer.remaining();
                            buffer.get(body, offset, Math.min(buffer.remaining(), this.body.length - offset));
                            if (this.received < this.body.length) {// 数据仍然不全，需要继续读取          
                                buffer.clear();
                                conn.read(buffer, attachment2, this);
                            } else {
                                success();
                            }
                            return;
                        }
                        checkResult(seqid, action, buffer);

                        final int respBodyLength = buffer.getInt();
                        buffer.getInt();  // bodyOffset
                        buffer.getInt();  // frameLength
                        final int retcode = buffer.getInt();
                        if (retcode != 0) throw new RuntimeException("remote service(" + action.method + ") deal error (retcode=" + retcode + ", retinfo=" + SncpResponse.getRetCodeInfo(retcode) + ")");

                        if (respBodyLength > buffer.remaining()) { // 数据不全，需要继续读取
                            this.body = new byte[respBodyLength];
                            this.received = buffer.remaining();
                            buffer.get(body, 0, this.received);
                            buffer.clear();
                            conn.read(buffer, attachment2, this);
                        } else {
                            this.body = new byte[respBodyLength];
                            buffer.get(body, 0, respBodyLength);
                            success();
                        }
                    }

                    public void success() {
                        future.set(this.body);
                        transport.offerBuffer(buffer);
                        transport.offerConnection(conn);
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment2) {
                        future.set(new RuntimeException(action.method + " sncp remote exec failed"));
                        transport.offerBuffer(buffer);
                        transport.offerConnection(conn);
                    }

                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer[] attachment) {
                exc.printStackTrace();
                transport.offerBuffer(buffer);
                transport.offerConnection(conn);
            }
        });
        return future;
    }

    private void checkResult(long seqid, final SncpAction action, ByteBuffer buffer) {
        long rseqid = buffer.getLong();
        if (rseqid != seqid) throw new RuntimeException("sncp(" + action.method + ") response.seqid = " + seqid + ", but request.seqid =" + rseqid);
        if (buffer.getChar() != HEADER_SIZE) throw new RuntimeException("sncp(" + action.method + ") buffer receive header.length not " + HEADER_SIZE);
        long rserviceid = buffer.getLong();
        if (rserviceid != serviceid) throw new RuntimeException("sncp(" + action.method + ") response.serviceid = " + serviceid + ", but request.serviceid =" + rserviceid);
        long rnameid = buffer.getLong();
        if (rnameid != nameid) throw new RuntimeException("sncp(" + action.method + ") response.nameid = " + nameid + ", but receive nameid =" + rnameid);
        long ractionid1 = buffer.getLong();
        long ractionid2 = buffer.getLong();
        if (!action.actionid.compare(ractionid1, ractionid2)) throw new RuntimeException("sncp(" + action.method + ") response.actionid = " + action.actionid + ", but request.actionid =(" + ractionid1 + "_" + ractionid2 + ")");
        buffer.getInt();  //地址
        buffer.getChar(); //端口
    }

    private void fillHeader(ByteBuffer buffer, long seqid, DLong actionid, int bodyLength, int bodyOffset, int frameLength) {
        //---------------------head----------------------------------
        final int currentpos = buffer.position();
        buffer.position(0);
        buffer.putLong(seqid); //序列号
        buffer.putChar((char) HEADER_SIZE); //header长度
        buffer.putLong(this.serviceid);
        buffer.putLong(this.nameid);
        buffer.putLong(actionid.getFirst());
        buffer.putLong(actionid.getSecond());
        buffer.put(addrBytes[0]);
        buffer.put(addrBytes[1]);
        buffer.put(addrBytes[2]);
        buffer.put(addrBytes[3]);
        buffer.putChar((char) this.addrPort);
        buffer.putInt(bodyLength); //body长度        
        buffer.putInt(bodyOffset);
        buffer.putInt(frameLength); //一帧数据的长度
        buffer.putInt(0); //结果码， 请求方固定传0
        buffer.position(currentpos);
    }
}
