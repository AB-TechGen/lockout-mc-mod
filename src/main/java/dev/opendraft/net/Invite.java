package dev.opendraft.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Invite codes like {@code 9ZK4-T2QM-8RD1}: the host's address on its own network and on the
 * internet, plus the port, written in letters and digits that are easy to read out. Joining with a
 * code tries both addresses at once, so friends in the same house and friends elsewhere use the
 * same code. A code hides the address from a quick glance at the screen, but it isn't a secret.
 */
public final class Invite {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // no I, L, O or U

    private Invite() {
    }

    /**
     * @param lan this computer's address on its network, or null
     * @param internet the public address, or null
     * @return the code, or null when there's no address to put in it
     */
    public static String encode(String lan, String internet, int port) {
        byte[] lanBytes = ipv4(lan);
        byte[] netBytes = ipv4(internet);
        if (lanBytes == null && netBytes == null) return null;
        if (lanBytes == null) lanBytes = new byte[4];
        int n = netBytes == null ? 7 : 11;
        byte[] data = new byte[n];
        System.arraycopy(lanBytes, 0, data, 0, 4);
        data[4] = (byte) (port >> 8);
        data[5] = (byte) port;
        if (netBytes != null) System.arraycopy(netBytes, 0, data, 6, 4);
        data[n - 1] = check(data, n - 1);
        String text = base32(data);
        // Short codes in groups of 4, long ones in groups of 6.
        int group = text.length() <= 12 ? 4 : 6;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (i > 0 && i % group == 0) out.append('-');
            out.append(text.charAt(i));
        }
        return out.toString();
    }

    /** The addresses in a code, internet first; null if the text isn't a valid code. */
    public static List<InetSocketAddress> decode(String code) {
        String text = code.trim().toUpperCase().replace("-", "").replace(" ", "")
                .replace('O', '0').replace('I', '1').replace('L', '1');
        byte[] data;
        if (text.length() == 12) data = unbase32(text, 7);
        else if (text.length() == 18) data = unbase32(text, 11);
        else return null;
        if (data == null || data[data.length - 1] != check(data, data.length - 1)) return null;
        int port = (data[4] & 0xFF) << 8 | data[5] & 0xFF;
        List<InetSocketAddress> out = new ArrayList<>();
        if (data.length == 11) out.add(InetSocketAddress.createUnresolved(dotted(data, 6), port));
        if (data[0] != 0) out.add(InetSocketAddress.createUnresolved(dotted(data, 0), port));
        return out.isEmpty() ? null : out;
    }

    /** True for an address on the internet, false for a home-network or shared provider address. */
    public static boolean isPublic(String ip) {
        byte[] b = ipv4(ip);
        if (b == null) return false;
        int a0 = b[0] & 0xFF, a1 = b[1] & 0xFF;
        if (a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224) return false;
        if (a0 == 100 && a1 >= 64 && a1 < 128) return false; // shared by the provider (carrier-grade NAT)
        if (a0 == 169 && a1 == 254) return false;
        if (a0 == 172 && a1 >= 16 && a1 < 32) return false;
        return !(a0 == 192 && a1 == 168);
    }

    /** This computer's address on its local network, or null. */
    public static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] ipv4(String ip) {
        if (ip == null) return null;
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) return null;
        byte[] b = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                b[i] = (byte) v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return b;
    }

    private static String dotted(byte[] b, int at) {
        return (b[at] & 0xFF) + "." + (b[at + 1] & 0xFF) + "." + (b[at + 2] & 0xFF) + "." + (b[at + 3] & 0xFF);
    }

    private static byte check(byte[] data, int len) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, len);
        return (byte) crc.getValue();
    }

    private static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = buffer << 8 | b & 0xFF;
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt(buffer >> (bits - 5) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt(buffer << (5 - bits) & 31));
        return out.toString();
    }

    private static byte[] unbase32(String text, int len) {
        byte[] out = new byte[len];
        int buffer = 0, bits = 0, n = 0;
        for (char c : text.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) return null;
            buffer = buffer << 5 | v;
            bits += 5;
            if (bits >= 8) {
                if (n == len) return null;
                out[n++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return n == len ? out : null;
    }
}
