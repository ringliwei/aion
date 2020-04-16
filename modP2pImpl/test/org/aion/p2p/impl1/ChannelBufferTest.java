package org.aion.p2p.impl1;

import static org.aion.p2p.Header.LEN;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.UUID;
import org.aion.p2p.Header;
import org.aion.p2p.impl1.ChannelBuffer.RouteStatus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

public class ChannelBufferTest {

    @Mock private Logger p2pLOG;

    private ChannelBuffer cb;
    private Random r;

    @Mock private Header header;

    private Header expectHeader;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        cb = new ChannelBuffer(p2pLOG);
        r = new Random();
    }

    private ByteBuffer genBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(r.nextInt(LEN << 1));
        int len = buffer.remaining() - LEN;
        expectHeader = Header.decode(genHeader(len < 0 ? 0 : len));
        // expectHeader.setLen(r.nextInt(P2pConstant.MAX_BODY_SIZE));

        buffer.put(expectHeader.encode(), 0, len < 0 ? buffer.remaining() : LEN);
        byte[] rByte = new byte[expectHeader.getLen()];
        r.nextBytes(rByte);
        buffer.put(rByte, 0, buffer.remaining() >= rByte.length ? rByte.length : buffer.remaining())
                .flip();

        return buffer;
    }

    private byte[] genHeader(int len) {
        when(header.getRoute()).thenReturn(r.nextInt());
        return ByteBuffer.allocate(LEN).putInt(header.getRoute()).putInt(len).array();
    }

    @Test
    public void testNodeIdHash() {
        int id = r.nextInt();
        cb.setNodeIdHash(id);
        assertEquals(id, cb.getNodeIdHash());
    }

    @Test
    public void testDisplayId() {
        String id = UUID.randomUUID().toString();
        cb.setDisplayId(id);
        assertEquals(id, cb.getDisplayId());
    }

    @Test
    public void testRefreshHeader() {
        cb.setHeader(header);
        cb.refreshHeader();
        assertNull(cb.getHeader());
    }

    @Test
    public void testRefreshBody() {
        cb.body = UUID.randomUUID().toString().getBytes();
        cb.refreshBody();
        assertNull(cb.body);
    }

    @Test
    public void testReadHead() {
        for (int i = 0; i < 100; i++) {
            cb.refreshHeader();
            ByteBuffer bb = genBuffer();
            cb.readHead(bb);
            if (bb.array().length >= LEN) {
                assertArrayEquals(expectHeader.encode(), cb.getHeader().encode());
            } else {
                assertNull(cb.getHeader());
            }
        }
    }

    @Test
    public void TestHeaderNotCompleted() {
        assertTrue(cb.isHeaderNotCompleted());
        cb.setHeader(header);
        assertFalse(cb.isHeaderNotCompleted());
    }

    @Test
    public void TestBodyNotCompleted() {
        when(header.getLen()).thenReturn(UUID.randomUUID().toString().getBytes().length);
        assertTrue(cb.isBodyNotCompleted());
        cb.setHeader(header);
        assertTrue(cb.isBodyNotCompleted());
        cb.body = UUID.randomUUID().toString().getBytes();
        assertFalse(cb.isBodyNotCompleted());
    }

    @Test
    public void testReadBody() {
        for (int i = 0; i < 100; i++) {
            cb.refreshHeader();
            cb.refreshBody();
            ByteBuffer bb = genBuffer();
            cb.readHead(bb);
            if (bb.array().length >= LEN) {
                assertArrayEquals(expectHeader.encode(), cb.getHeader().encode());
                cb.readBody(bb);
                assertNotNull(cb.body);
                assertEquals(cb.getHeader().getLen(), cb.body.length);
            } else {
                assertNull(cb.getHeader());
            }
        }
    }

    @Test
    public void testReadBodyNotCompleted() {
        ByteBuffer bb = genBuffer();
        cb.readBody(bb);
        assertNull(cb.body);
    }

    @Test
    public void testShouldRoute() throws InterruptedException {
        assertTrue(cb.shouldRoute(1, 1));
        assertTrue(cb.shouldRoute(1, 1));
        assertFalse(cb.shouldRoute(1, 1));
        Thread.sleep(1001);
        assertTrue(cb.shouldRoute(1, 2));

        cb.shouldRoute(1, 2);
        cb.shouldRoute(1, 2);

        RouteStatus rs = cb.getRouteCount(1);
        assertNotNull(rs);
        assertEquals(2, rs.count);
    }
}
