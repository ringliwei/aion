package org.aion.p2p.impl1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aion.p2p.INode;
import org.aion.p2p.INodeMgr;
import org.aion.p2p.P2pConstant;
import org.aion.p2p.impl.zero.msg.ReqHandshake1;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

public class TaskConnectPeersTest {

    @Mock private Logger p2pLOG;

    @Mock private INodeMgr nodeMgr;

    @Mock private P2pMgr p2pMgr;

    @Mock private BlockingQueue<MsgOut> sendMsgQue;

    @Mock private ReqHandshake1 rhs;

    @Mock private INode node;

    private ServerSocketChannel ssc;

    private Thread listen;

    private Selector selector;

    private final Random r = new Random();

    private int port;

    public class ThreadTCPServer extends Thread {

        SocketChannel sc;
        Selector selector;

        ThreadTCPServer(Selector _selector) {
            selector = _selector;
        }

        @Override
        public void run() {
            while (!this.isInterrupted()) {
                try {
                    if (this.selector.selectNow() == 0) {
                        Thread.sleep(0, 1);
                        continue;
                    }
                } catch (IOException | ClosedSelectorException e) {
                    p2pLOG.debug("inbound-select-exception", e);
                    continue;
                } catch (InterruptedException e) {
                    p2pLOG.error("inbound thread sleep exception ", e);
                    return;
                }

                Iterator itor = this.selector.selectedKeys().iterator();
                while (itor.hasNext()) {
                    SelectionKey key;
                    try {
                        key = (SelectionKey) itor.next();
                        if (key.isAcceptable()) {
                            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                            sc = ssc.accept();
                            if (sc != null) {
                                sc.configureBlocking(false);
                                sc.socket().setSoTimeout(10_000);
                                sc.socket().setReceiveBufferSize(P2pConstant.RECV_BUFFER_SIZE);
                                sc.socket().setSendBufferSize(P2pConstant.SEND_BUFFER_SIZE);

                                SelectionKey sk = sc.register(this.selector, SelectionKey.OP_READ);
                                sk.attach(new ChannelBuffer(p2pLOG));
                                System.out.println("socket connected!");
                            }
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    itor.remove();
                }
            }
        }
    }

    @Before
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);

        System.setProperty("java.net.preferIPv4Stack", "true");
        ssc = ServerSocketChannel.open();
        assertNotNull(ssc);
        ssc.configureBlocking(false);
        ssc.socket().setReuseAddress(true);
        port = 50000 + r.nextInt(15535);
        ssc.socket().bind(new InetSocketAddress(port));
        // Create the selector
        selector = Selector.open();
        assertNotNull(selector);
        ssc.register(selector, SelectionKey.OP_ACCEPT);

        listen = new ThreadTCPServer(selector);
        assertNotNull(listen);
        listen.start();
    }

    @After
    public void tearDown() throws IOException, InterruptedException {
        listen.interrupt();
        Thread.sleep(1000);
        ssc.close();
    }

    @Test(timeout = 10_000)
    public void testRun() throws InterruptedException {
        AtomicBoolean atb = new AtomicBoolean(true);
        TaskConnectPeers tcp =
                new TaskConnectPeers(p2pLOG, p2pMgr, atb, nodeMgr, 128, selector, rhs);
        assertNotNull(tcp);

        Thread t = new Thread(tcp);
        t.start();
        assertTrue(t.isAlive());
        Thread.sleep(10);
        atb.set(false);
        while (!t.getState().toString().equals("TERMINATED")) {
            Thread.sleep(100);
        }
    }

    @Test(timeout = 10_000)
    public void testRun1() throws InterruptedException {
        AtomicBoolean atb = new AtomicBoolean(true);
        TaskConnectPeers tcp =
                new TaskConnectPeers(p2pLOG, p2pMgr, atb, nodeMgr, 128, selector, rhs);
        assertNotNull(tcp);

        when(nodeMgr.activeNodesSize()).thenReturn(128);
        when(nodeMgr.tempNodesTake()).thenReturn(null);

        when(node.getIdHash()).thenReturn(1);
        when(node.getPort()).thenReturn(port);
        when(node.getIpStr()).thenReturn("127.0.0.1");
        when(nodeMgr.notAtOutboundList(node.getIdHash())).thenReturn(true);
        when(nodeMgr.notActiveNode(node.getIdHash())).thenReturn(true);

        Thread t = new Thread(tcp);
        t.start();
        assertTrue(t.isAlive());
        Thread.sleep(10);

        // should see the loop continue every sec
        Thread.sleep(2000);

        when(nodeMgr.activeNodesSize()).thenReturn(127);
        // should see the loop continue every sec due to null node been taken
        Thread.sleep(2000);

        when(node.getIdShort()).thenReturn("1");
        when(nodeMgr.tempNodesTake()).thenReturn(node);
        when(node.getIfFromBootList()).thenReturn(true);

        Thread.sleep(2000);

        atb.set(false);
        while (!t.getState().toString().equals("TERMINATED")) {
            Thread.sleep(100);
        }
    }

    @Test(timeout = 10_000)
    public void testRunException() throws InterruptedException {
        AtomicBoolean atb = new AtomicBoolean(true);
        TaskConnectPeers tcp =
                new TaskConnectPeers(p2pLOG, p2pMgr, atb, nodeMgr, 128, selector, rhs);
        assertNotNull(tcp);

        when(node.getIdHash()).thenReturn(1);
        when(node.getPort()).thenReturn(port);
        when(node.getIpStr()).thenReturn("127.0.0.1");
        when(nodeMgr.notAtOutboundList(node.getIdHash())).thenReturn(true);
        when(nodeMgr.notActiveNode(node.getIdHash())).thenReturn(true);

        Thread t = new Thread(tcp);
        t.start();
        assertTrue(t.isAlive());
        Thread.sleep(10);

        // should see the loop continue every sec
        Thread.sleep(1000);

        when(nodeMgr.activeNodesSize()).thenReturn(127);
        // should see the loop continue every sec due to null node been taken
        Thread.sleep(1000);

        when(node.getIdShort()).thenReturn("1");
        when(nodeMgr.tempNodesTake()).thenReturn(node);
        when(node.getIfFromBootList()).thenReturn(true);
        when(sendMsgQue.offer(any(MsgOut.class))).thenThrow(new NullPointerException("exception"));

        Thread.sleep(2000);

        atb.set(false);
        while (!t.getState().toString().equals("TERMINATED")) {
            Thread.sleep(100);
        }
    }

    @Test(timeout = 10_000)
    public void testRunException2() throws InterruptedException {
        AtomicBoolean atb = new AtomicBoolean(true);
        TaskConnectPeers tcp =
                new TaskConnectPeers(p2pLOG, p2pMgr, atb, nodeMgr, 128, selector, rhs);
        assertNotNull(tcp);

        when(node.getIdHash()).thenReturn(1);
        when(node.getPort()).thenReturn(port);
        when(node.getIpStr()).thenReturn("127.0.0.1");
        when(nodeMgr.notAtOutboundList(node.getIdHash())).thenReturn(true);
        when(nodeMgr.notActiveNode(node.getIdHash())).thenReturn(true);

        Thread t = new Thread(tcp);
        t.start();
        assertTrue(t.isAlive());
        Thread.sleep(10);

        // should see the loop continue every sec
        Thread.sleep(1000);
        when(nodeMgr.tempNodesTake()).thenThrow(new NullPointerException("exception"));
        atb.set(false);
        Thread.sleep(3000);
        assertEquals("TERMINATED", t.getState().toString());
    }
}
