package com.suvo.agent.netgate;

/**
 * Your Friend — Original project by Suvo.
 *
 * Reads just enough of an IP packet (IPv4 or IPv6) to know its connection: protocol, source and
 * destination address and port, and whether it opens a TCP connection. Pure Java (unit-tested).
 */
public final class Packet {

    public static final int TCP = 6, UDP = 17;

    public int version;
    public int protocol;
    public byte[] src, dst;
    public int srcPort = -1, dstPort = -1;
    /** A TCP SYN without ACK: a new connection. */
    public boolean opensTcp;

    private Packet() {
    }

    /** Null when it is not an IP packet we can read. */
    public static Packet parse(byte[] b, int len) {
        if (len < 1) return null;
        Packet p = new Packet();
        p.version = (b[0] >> 4) & 0xF;
        int l4;
        if (p.version == 4) {
            if (len < 20) return null;
            int ihl = (b[0] & 0xF) * 4;
            if (ihl < 20 || len < ihl) return null;
            p.protocol = b[9] & 0xFF;
            p.src = slice(b, 12, 4);
            p.dst = slice(b, 16, 4);
            // A fragment that is not the first has no ports.
            int frag = ((b[6] & 0x1F) << 8) | (b[7] & 0xFF);
            if (frag != 0) return p;
            l4 = ihl;
        } else if (p.version == 6) {
            if (len < 40) return null;
            p.protocol = b[6] & 0xFF;
            p.src = slice(b, 8, 16);
            p.dst = slice(b, 24, 16);
            l4 = 40;
        } else {
            return null;
        }
        if ((p.protocol == TCP || p.protocol == UDP) && len >= l4 + 4) {
            p.srcPort = ((b[l4] & 0xFF) << 8) | (b[l4 + 1] & 0xFF);
            p.dstPort = ((b[l4 + 2] & 0xFF) << 8) | (b[l4 + 3] & 0xFF);
            if (p.protocol == TCP && len >= l4 + 14) {
                int flags = b[l4 + 13] & 0xFF;
                p.opensTcp = (flags & 0x02) != 0 && (flags & 0x10) == 0;
            }
        }
        return p;
    }

    private static byte[] slice(byte[] b, int from, int n) {
        byte[] o = new byte[n];
        System.arraycopy(b, from, o, 0, n);
        return o;
    }

    /** The connection this packet belongs to, seen from the app (same for every packet of it). */
    public String flowKey() {
        StringBuilder s = new StringBuilder();
        s.append(protocol).append('|');
        hex(s, src);
        s.append(':').append(srcPort).append('>');
        hex(s, dst);
        s.append(':').append(dstPort);
        return s.toString();
    }

    private static void hex(StringBuilder s, byte[] a) {
        for (byte x : a) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
    }

    /** True for the address (and port 53) given to apps as their DNS server. */
    public boolean isDnsTo(byte[] dnsV4) {
        return dstPort == 53 && dst != null && java.util.Arrays.equals(dst, dnsV4);
    }
}
