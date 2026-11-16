package com.suvo.agent.netgate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PacketTest {

    private static byte[] ipv4Tcp(int flags) {
        byte[] b = new byte[40];
        b[0] = 0x45;
        b[9] = 6;
        b[12] = 10; b[13] = 0; b[14] = 0; b[15] = 2;      // src 10.0.0.2
        b[16] = (byte) 142; b[17] = (byte) 250; b[18] = 1; b[19] = 1; // dst
        b[20] = (byte) 0xC3; b[21] = 0x50;                // src port 50000
        b[22] = 0x01; b[23] = (byte) 0xBB;                // dst port 443
        b[33] = (byte) flags;
        return b;
    }

    @Test
    public void readsIpv4Tcp() {
        Packet p = Packet.parse(ipv4Tcp(0x02), 40);
        assertNotNull(p);
        assertEquals(4, p.version);
        assertEquals(Packet.TCP, p.protocol);
        assertEquals(50000, p.srcPort);
        assertEquals(443, p.dstPort);
        assertTrue(p.opensTcp);
        assertFalse(Packet.parse(ipv4Tcp(0x12), 40).opensTcp); // SYN+ACK is not a new connection
        assertEquals(p.flowKey(), Packet.parse(ipv4Tcp(0x10), 40).flowKey());
    }

    @Test
    public void readsIpv6UdpAndDns() {
        byte[] b = new byte[48];
        b[0] = 0x60;
        b[6] = 17;
        b[8] = (byte) 0xfc; b[23] = 1;
        b[24] = 0x20; b[25] = 0x01;
        b[40] = 0x30; b[41] = 0x39; // 12345
        b[42] = 0; b[43] = 53;
        Packet p = Packet.parse(b, 48);
        assertEquals(6, p.version);
        assertEquals(Packet.UDP, p.protocol);
        assertEquals(12345, p.srcPort);
        assertEquals(53, p.dstPort);
        assertFalse(p.isDnsTo(new byte[]{(byte) 198, 18, 0, 2}));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(Packet.parse(new byte[]{0x45, 0, 0}, 3));
        assertNull(Packet.parse(new byte[]{0x15, 0, 0, 0}, 4));
        assertNull(Packet.parse(new byte[0], 0));
    }
}
