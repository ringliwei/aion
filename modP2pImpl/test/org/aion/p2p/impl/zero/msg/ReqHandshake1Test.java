package org.aion.p2p.impl.zero.msg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.aion.p2p.Ctrl;
import org.aion.p2p.Ver;
import org.aion.p2p.impl.comm.Act;
import org.aion.p2p.impl.comm.Node;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

/** @author chris */
public class ReqHandshake1Test {

    private byte[] validNodeId = UUID.randomUUID().toString().getBytes();

    private int netId = ThreadLocalRandom.current().nextInt();

    private byte[] invalidNodeId = UUID.randomUUID().toString().substring(0, 34).getBytes();

    private int port = ThreadLocalRandom.current().nextInt();

    @Mock
    private Logger p2pLOG;

    private String randomIp =
            ThreadLocalRandom.current().nextInt(0, 256)
                    + "."
                    + ThreadLocalRandom.current().nextInt(0, 256)
                    + "."
                    + ThreadLocalRandom.current().nextInt(0, 256)
                    + "."
                    + ThreadLocalRandom.current().nextInt(0, 256);

    private byte[] randomRevision;

    private List<Short> randomVersions;

    @Before
    public void reqHandshake2Test() {
        MockitoAnnotations.initMocks(this);
        randomRevision = new byte[Byte.MAX_VALUE];
        ThreadLocalRandom.current().nextBytes(randomRevision);
        randomVersions = new ArrayList<>();
        for (byte i = 0; i < 127; i++) {
            randomVersions.add((short) ThreadLocalRandom.current().nextInt(Short.MAX_VALUE + 1));
        }
    }

    @Test
    public void testRoute() {
        System.out.println("randomRevision " + Arrays.toString(randomRevision));
        ReqHandshake1 req =
                new ReqHandshake1(
                        validNodeId,
                        netId,
                        Node.ipStrToBytes(randomIp),
                        port,
                        randomRevision,
                        randomVersions);
        assertEquals(Ver.V0, req.getHeader().getVer());
        assertEquals(Ctrl.NET, req.getHeader().getCtrl());
        assertEquals(Act.REQ_HANDSHAKE, req.getHeader().getAction());
    }

    @Test
    public void testValidEncodeDecode() {

        ReqHandshake1 req1 =
                new ReqHandshake1(
                        validNodeId,
                        netId,
                        Node.ipStrToBytes(randomIp),
                        port,
                        randomRevision,
                        randomVersions);
        byte[] bytes = req1.encode();

        ReqHandshake1 req2 = ReqHandshake1.decode(bytes, p2pLOG);
        assertNotNull(req2.getNodeId());
        assertArrayEquals(req1.getNodeId(), req2.getNodeId());
        assertArrayEquals(req1.getIp(), req2.getIp());
        assertEquals(req1.getNetId(), req2.getNetId());
        assertEquals(req1.getPort(), req2.getPort());
        assertArrayEquals(req1.getRevision(), req2.getRevision());
    }

    @Test
    public void testInvalidEncodeDecode() {

        ReqHandshake1 req1 =
                new ReqHandshake1(
                        invalidNodeId,
                        netId,
                        Node.ipStrToBytes(randomIp),
                        port,
                        randomRevision,
                        randomVersions);
        byte[] bytes = req1.encode();
        assertNull(bytes);

        ReqHandshake1 req2 = ReqHandshake1.decode(bytes, p2pLOG);
        assertNull(req2);
    }

    @Test
    public void testRepeatEncodeDecode() {

        // Repeated Encode and Decode Units
        for (int i = 0; i < 100; i++) {
            testValidEncodeDecode();
            testInvalidEncodeDecode();
            testRoute();
        }
    }

    @Test
    public void testdecode() {
        ReqHandshake rhs = ReqHandshake.decode(null);
        assertNull(rhs);

        rhs = ReqHandshake.decode(new byte[ReqHandshake.LEN - 1]);
        assertNull(rhs);
    }

    @Test
    public void testdecodeException() {
        byte[] msg = new byte[ReqHandshake1.LEN + 2];
        msg[ReqHandshake1.LEN + 1] = 2; // versions Length
        ReqHandshake rhs1 = ReqHandshake1.decode(msg);
        assertNull(rhs1);
    }
}
