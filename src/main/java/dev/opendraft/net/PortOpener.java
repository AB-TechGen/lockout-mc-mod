package dev.opendraft.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the home router to forward the synchronizer's port to this computer, using UPnP (the
 * same thing game consoles do), so friends on other networks can connect without anyone setting
 * up port forwarding. Most home routers allow it. When one doesn't, {@link #problem} says why.
 */
public final class PortOpener {
    private static final int TIMEOUT_MS = 3000;
    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
    };
    private static final Pattern SERVICE = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** The router's address on the internet, or null if no router answered. */
    public final String externalIp;
    /** This computer's address on the router's network, or null. */
    public final String localIp;
    /** Why the port couldn't be opened, or null when it was. */
    public final String problem;

    private final int port;
    private final String controlUrl;
    private final String serviceType;

    private PortOpener(int port, String controlUrl, String serviceType, String localIp, String externalIp, String problem) {
        this.port = port;
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
        this.localIp = localIp;
        this.externalIp = externalIp;
        this.problem = problem;
    }

    public boolean opened() {
        return problem == null;
    }

    /** Finds the router and forwards the TCP port. Never throws; takes a few seconds at most. */
    public static PortOpener open(int port) {
        List<String> locations;
        try {
            locations = discover();
        } catch (IOException e) {
            return failed(port, "your network didn't let the game look for the router (" + e.getMessage() + ")");
        }
        if (locations.isEmpty()) return failed(port, "no router answered, so UPnP may be turned off");
        String last = "the router didn't accept the request";
        for (String location : locations) {
            try {
                Gateway gw = Gateway.read(location);
                if (gw != null) return forward(port, gw);
                last = "the device that answered can't forward ports";
            } catch (IOException e) {
                last = "the router didn't answer properly (" + e.getMessage() + ")";
            }
        }
        return failed(port, last);
    }

    /** Opens the port on a router whose description is at {@code location}. Used directly by tests. */
    static PortOpener openAt(int port, String location) throws IOException {
        Gateway gw = Gateway.read(location);
        if (gw == null) return failed(port, "that device isn't a router");
        return forward(port, gw);
    }

    private static PortOpener forward(int port, Gateway gw) throws IOException {
        String local = localAddressTowards(gw.host);
        String external = null;
        Soap ip = soap(gw.controlUrl, gw.serviceType, "GetExternalIPAddress", "");
        if (ip.ok) external = tag(ip.body, "NewExternalIPAddress");
        if (local == null) return new PortOpener(port, null, null, null, external, "couldn't tell which address this computer has");

        // A lease of 0 means "until removed"; some newer routers want a fixed length instead.
        Soap add = addMapping(gw, port, local, 0);
        if (!add.ok) add = addMapping(gw, port, local, 86_400);
        if (!add.ok) {
            String why = switch (add.errorCode) {
                case "718" -> "another device on your network already has port " + port + " open on the router; pick another port";
                case "606", "501" -> "the router doesn't allow programs to open ports (UPnP may be set to read-only)";
                default -> "the router said no" + (add.errorCode.isEmpty() ? "" : " (error " + add.errorCode + ")");
            };
            return new PortOpener(port, null, null, local, external, why);
        }
        return new PortOpener(port, gw.controlUrl, gw.serviceType, local, external, null);
    }

    private static Soap addMapping(Gateway gw, int port, String local, int lease) throws IOException {
        return soap(gw.controlUrl, gw.serviceType, "AddPortMapping",
                "<NewRemoteHost></NewRemoteHost>"
                        + "<NewExternalPort>" + port + "</NewExternalPort>"
                        + "<NewProtocol>TCP</NewProtocol>"
                        + "<NewInternalPort>" + port + "</NewInternalPort>"
                        + "<NewInternalClient>" + local + "</NewInternalClient>"
                        + "<NewEnabled>1</NewEnabled>"
                        + "<NewPortMappingDescription>OpenDraft</NewPortMappingDescription>"
                        + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>");
    }

    /** Takes the forwarding off the router again. */
    public void close() {
        if (controlUrl == null) return;
        try {
            soap(controlUrl, serviceType, "DeletePortMapping",
                    "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort><NewProtocol>TCP</NewProtocol>");
        } catch (IOException ignored) {
        }
    }

    private static PortOpener failed(int port, String why) {
        return new PortOpener(port, null, null, null, null, why);
    }

    /** Asks every UPnP router on the network to say where its description is. */
    private static List<String> discover() throws IOException {
        Set<String> locations = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(400);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (String st : SEARCH_TARGETS) {
                byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                        + st + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(msg, msg.length, group, 1900));
            }
            long deadline = System.currentTimeMillis() + 2500;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    if (!locations.isEmpty()) break; // everyone who's going to answer quickly has
                    continue;
                }
                String location = header(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII), "LOCATION");
                if (location != null && location.startsWith("http://")) locations.add(location);
            }
        }
        return new ArrayList<>(locations);
    }

    static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return null;
    }

    /** The address this computer uses to reach {@code host}, found without sending anything. */
    private static String localAddressTowards(String host) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(host), 1900);
            InetAddress a = s.getLocalAddress();
            return a == null || a.isAnyLocalAddress() ? null : a.getHostAddress();
        } catch (IOException e) {
            return null;
        }
    }

    /** The router's port-forwarding service, read from its description. */
    record Gateway(String host, String controlUrl, String serviceType) {
        static Gateway read(String location) throws IOException {
            String xml = get(location);
            String best = null, bestType = null;
            int bestRank = Integer.MAX_VALUE;
            Matcher m = SERVICE.matcher(xml);
            while (m.find()) {
                String type = tag(m.group(1), "serviceType");
                String control = tag(m.group(1), "controlURL");
                if (type == null || control == null) continue;
                int rank = type.contains(":WANIPConnection:2") ? 0 : type.contains(":WANIPConnection:") ? 1
                        : type.contains(":WANPPPConnection:") ? 2 : -1;
                if (rank >= 0 && rank < bestRank) {
                    bestRank = rank;
                    best = control;
                    bestType = type;
                }
            }
            if (best == null) return null;
            String base = tag(xml, "URLBase");
            URI baseUri = URI.create(base != null && !base.isEmpty() ? base : location);
            return new Gateway(URI.create(location).getHost(), baseUri.resolve(best.trim()).toString(), bestType.trim());
        }
    }

    record Soap(boolean ok, String body, String errorCode) {
    }

    private static Soap soap(String controlUrl, String serviceType, String action, String args) throws IOException {
        String envelope = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) URI.create(controlUrl).toURL().openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
        try (OutputStream out = c.getOutputStream()) {
            out.write(envelope.getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : read(in);
        c.disconnect();
        if (status == 200) return new Soap(true, body, "");
        String code = tag(body, "errorCode");
        return new Soap(false, body, code == null ? "" : code.trim());
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = c.getInputStream()) {
            return read(in);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        // Router descriptions are a few kilobytes; anything far bigger isn't one.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > 256 * 1024) throw new IOException("reply too large");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The text inside the first {@code <name>} element, ignoring any namespace prefix. */
    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<(?:[\\w-]+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?" + name + ">",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** This network's address on the internet, from a free lookup service, or null. */
    public static String publicAddress() {
        try {
            String ip = get("https://api.ipify.org").trim();
            return Invite.isPublic(ip) ? ip : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
