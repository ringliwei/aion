package org.aion.p2p.impl1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aion.p2p.Ctrl;
import org.aion.p2p.Handler;
import org.aion.p2p.Header;
import org.aion.p2p.INodeMgr;
import org.aion.p2p.P2pConstant;
import org.aion.p2p.Ver;
import org.slf4j.Logger;

public class TaskInbound implements Runnable {

    private final Logger p2pLOG, surveyLog;
    private final P2pMgr mgr;
    private final Selector selector;
    private final INodeMgr nodeMgr;
    private final Map<Integer, List<Handler>> handlers;
    private final AtomicBoolean start;

    // used to impose a low limit to this type of messages
    private static final int ACT_BROADCAST_BLOCK = 7;
    private static final int CTRL_SYNC = 1;

    // used when survey logging
    private static final long MIN_DURATION = 60_000_000_000L; // 60 seconds
    private long waitTime = 0, processTime = 0;

    public TaskInbound(
            final Logger p2pLOG,
            final Logger surveyLog,
            final P2pMgr _mgr,
            final Selector _selector,
            final AtomicBoolean _start,
            final INodeMgr _nodeMgr,
            final Map<Integer, List<Handler>> _handlers) {

        this.p2pLOG = p2pLOG;
        this.surveyLog = surveyLog;
        this.mgr = _mgr;
        this.selector = _selector;
        this.start = _start;
        this.nodeMgr = _nodeMgr;
        this.handlers = _handlers;
    }

    @Override
    public void run() {
        // for runtime survey information
        long startTime, duration;

        // readBuffer buffer pre-alloc. @ max_body_size
        ByteBuffer readBuf = ByteBuffer.allocate(P2pConstant.MAX_BODY_SIZE);

        while (start.get()) {

            startTime = System.nanoTime();
            try {
                // timeout set to 0.1 second
                if (this.selector.select(100) == 0) {
                    duration = System.nanoTime() - startTime;
                    waitTime += duration;
                    continue;
                }
            } catch (IOException | ClosedSelectorException e) {
                p2pLOG.debug("inbound-select-exception.", e);
                continue;
            }
            duration = System.nanoTime() - startTime;
            waitTime += duration;
            if (waitTime > MIN_DURATION) { // print and reset total time so far
                surveyLog.debug("TaskInbound: find selectors, duration = {} ns.", waitTime);
                waitTime = 0;
            }

            startTime = System.nanoTime();
            try {
                Iterator<SelectionKey> keys = this.selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    ChannelBuffer cb = null;
                    SelectionKey key = null;
                    try {
                        key = keys.next();
                        if (!key.isValid()) {
                            continue;
                        }

                        if (key.isAcceptable()) {
                            mgr.acceptConnection((ServerSocketChannel) key.channel());
                        }

                        if (key.isReadable()) {
                            cb = (ChannelBuffer) key.attachment();
                            if (cb == null) {
                                p2pLOG.error("inbound exception: attachment is null");
                                continue;
                            }
                            readBuffer(key, cb, readBuf);
                        }
                    } catch (Exception e) {
                        this.mgr.closeSocket(
                                key != null ? (SocketChannel) key.channel() : null,
                                (cb != null ? cb.getDisplayId() : null) + "-read-msg-exception ",
                                e);
                        if (cb != null) {
                            cb.setClosed();
                        }
                    } finally {
                        keys.remove();
                    }
                }
            } catch (ClosedSelectorException ex) {
                p2pLOG.error("inbound ClosedSelectorException.", ex);
            }
            duration = System.nanoTime() - startTime;
            processTime += duration;
            if (processTime > MIN_DURATION) { // print and reset total time so far
                surveyLog.debug("TaskInbound: process incoming msg, duration = {} ns.", processTime);
                processTime = 0;
            }
        }

        // print remaining total times
        surveyLog.debug("TaskInbound: find selectors, duration = {} ns.", waitTime);
        surveyLog.debug("TaskInbound: process incoming msg, duration = {} ns.", processTime);

        p2pLOG.info("p2p-pi shutdown");
    }

    private int readHeader(final ChannelBuffer _cb, final ByteBuffer _readBuf, int cnt) {

        if (cnt < Header.LEN) {
            return cnt;
        }

        int origPos = _readBuf.position();

        int startP = origPos - cnt;

        _readBuf.position(startP);

        _cb.readHead(_readBuf);

        _readBuf.position(origPos);

        return cnt - Header.LEN;
    }

    private int readBody(final ChannelBuffer _cb, ByteBuffer _readBuf, int _cnt) {

        int bodyLen = _cb.getHeader().getLen();

        // some msg have nobody.
        if (bodyLen == 0) {
            _cb.body = new byte[0];
            return _cnt;
        }

        if (_cnt < bodyLen) {
            return _cnt;
        }

        int origPos = _readBuf.position();
        int startP = origPos - _cnt;
        _readBuf.position(startP);
        _cb.readBody(_readBuf);
        _readBuf.position(origPos);
        return _cnt - bodyLen;
    }

    private void readBuffer(
            final SelectionKey _sk, final ChannelBuffer _cb, final ByteBuffer _readBuf)
            throws Exception {

        _readBuf.rewind();

        SocketChannel sc = (SocketChannel) _sk.channel();

        int r;
        int cnt = 0;
        do {
            r = sc.read(_readBuf);
            cnt += r;
        } while (r > 0);

        if (cnt < 1) {
            return;
        }

        int remainBufAll = _cb.getBuffRemain() + cnt;
        ByteBuffer bufferAll = calBuffer(_cb, _readBuf, cnt);

        do {
            r = readMsg(_sk, bufferAll, remainBufAll);
            if (remainBufAll == r) {
                break;
            } else {
                remainBufAll = r;
            }
        } while (r > 0);

        _cb.setBuffRemain(r);

        if (r != 0) {
            // there are no perfect cycling buffer in jdk
            // yet.
            // simply just buff move for now.
            // @TODO: looking for more efficient way.

            int currPos = bufferAll.position();
            _cb.setRemainBuffer(new byte[r]);
            bufferAll.position(currPos - r);
            bufferAll.get(_cb.getRemainBuffer());
        }

        _readBuf.rewind();
    }

    private int readMsg(SelectionKey _sk, ByteBuffer _readBuf, int _cnt) throws IOException {
        ChannelBuffer cb = (ChannelBuffer) _sk.attachment();
        if (cb == null) {
            throw new IOException("attachment is null");
        }

        int readCnt;
        if (cb.isHeaderNotCompleted()) {
            readCnt = readHeader(cb, _readBuf, _cnt);
        } else {
            readCnt = _cnt;
        }

        if (cb.isBodyNotCompleted()) {
            readCnt = readBody(cb, _readBuf, readCnt);
        }

        if (cb.isBodyNotCompleted()) {
            return readCnt;
        }

        handleMsg(_sk, cb);

        return readCnt;
    }

    private void handleMsg(SelectionKey _sk, ChannelBuffer _cb) {

        Header h = _cb.getHeader();
        byte[] bodyBytes = _cb.body;

        _cb.refreshHeader();
        _cb.refreshBody();

        int maxRequestsPerSecond = 0;

        // TODO: refactor to remove knowledge of sync message types
        if (h.getCtrl() == CTRL_SYNC && h.getAction() == ACT_BROADCAST_BLOCK) {
            maxRequestsPerSecond = P2pConstant.READ_MAX_RATE;
        } else {
            maxRequestsPerSecond = P2pConstant.READ_MAX_RATE_TXBC;
        }

        boolean underRC = _cb.shouldRoute(h.getRoute(), maxRequestsPerSecond);

        if (!underRC) {
            if (p2pLOG.isDebugEnabled()) {
                p2pLOG.debug(
                        "over-called-route={}-{}-{} calls={} node={}",
                        h.getVer(),
                        h.getCtrl(),
                        h.getAction(),
                        _cb.getRouteCount(h.getRoute()).count,
                        _cb.getDisplayId());
            }
            return;
        }

        switch (h.getVer()) {
            case Ver.V0:
                switch (h.getCtrl()) {
                    case Ctrl.NET:
                        try {
                            mgr.handleP2pMessage(_sk, h.getAction(), bodyBytes);
                        } catch (Exception ex) {
                            if (p2pLOG.isDebugEnabled()) {
                                p2pLOG.debug("handle-p2p-msg error.", ex);
                            }
                        }
                        break;
                    case Ctrl.SYNC:
                        if (!handlers.containsKey(h.getRoute())) {
                            if (p2pLOG.isDebugEnabled()) {
                                p2pLOG.debug(
                                        "unregistered-route={}-{}-{} node={}",
                                        h.getVer(),
                                        h.getCtrl(),
                                        h.getAction(),
                                        _cb.getDisplayId());
                            }
                            return;
                        }

                        mgr.handleKernelMessage(_cb.getNodeIdHash(), h.getRoute(), bodyBytes);
                        break;
                    default:
                        if (p2pLOG.isDebugEnabled()) {
                            p2pLOG.debug(
                                    "invalid-route={}-{}-{} node={}",
                                    h.getVer(),
                                    h.getCtrl(),
                                    h.getAction(),
                                    _cb.getDisplayId());
                        }
                        break;
                }
                break;
            default:
                if (p2pLOG.isDebugEnabled()) {
                    p2pLOG.debug("unhandled-ver={} node={}", h.getVer(), _cb.getDisplayId());
                }

                break;
        }
    }

    private ByteBuffer calBuffer(ChannelBuffer _cb, ByteBuffer _readBuf, int _cnt) {
        ByteBuffer r;
        if (_cb.getBuffRemain() != 0) {
            byte[] alreadyRead = new byte[_cnt];
            _readBuf.position(0);
            _readBuf.get(alreadyRead);
            r = ByteBuffer.allocate(_cb.getBuffRemain() + _cnt);
            r.put(_cb.getRemainBuffer());
            r.put(alreadyRead);
        } else {
            r = _readBuf;
        }

        return r;
    }
}
