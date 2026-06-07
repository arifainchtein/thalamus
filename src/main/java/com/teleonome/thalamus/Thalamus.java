package com.teleonome.thalamus;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.log4j.spi.LoggingEvent;

/**
 * THALAMUS: The Live Sensory Relay Station
 *
 * Three-panel TUI dashboard:
 *   [1] Memory — RSS for each Organ process + Denome file freshness
 *   [2] MQTT   — live feed of all MQTT topics from the local broker
 *   [3] Log4J  — log stream received via SocketAppender on port 4712
 *
 * Interactive prompt at the bottom with live autocomplete (Tab to accept).
 */
public class Thalamus {

    // =========================================================
    // Constants
    // =========================================================
    private static final String[] ORGANS = {
        "Heart.jar", "Hypothalamus.jar", "Hippocampus.jar", "Medula.jar", "Cerebellum.jar", "tomcat"
    };
    // Layout — all column widths are derived from the detected terminal width
    private static final int    W;            // terminal width, detected at startup
    private static final String SEP_D;        // ══════...  (W chars)
    private static final String SEP_S;        // ──────...  (W chars)
    // Memory panel columns
    private static final int    LAST_W   = 7; // "last update" age column, e.g. "   42s"
    private static final int    BAR_W;        // health-bar block count
    private static final int    LEFT_COL;     // visible chars in left organ column
    private static final int    RIGHT_COL;    // visible chars in right source column
    // MQTT panel columns
    private static final int    MQTT_TOPIC_W;
    private static final int    MQTT_PAYLOAD_W;
    // Log panel columns
    private static final int    LOG_SOURCE_W  = 30;
    private static final int    LOG_MSG_W;

    static {
        // Detect terminal width via tput; fall back to 200 (wide/full-screen default)
        int w = 200;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "tput cols </dev/tty"});
            String line = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream())).readLine();
            if (line != null) w = Integer.parseInt(line.trim());
        } catch (Exception ignored) {}
        W = w;

        // Separators
        StringBuilder d = new StringBuilder(W), s = new StringBuilder(W);
        for (int i = 0; i < W; i++) { d.append('═'); s.append('─'); }
        SEP_D = d.toString(); SEP_S = s.toString();

        // Memory panel: bar capped at 40 blocks; left col wraps it exactly
        int bw = Math.min(40, W / 2 - 33);
        BAR_W     = Math.max(10, bw);
        // 1+16+1+7+1+[BAR_W+2]+1+3+1 (pct) +1+LAST_W (age) = BAR_W+34+LAST_W
        LEFT_COL  = BAR_W + 34 + LAST_W;
        RIGHT_COL = W - LEFT_COL - 4;            // " │  " = 4 chars

        // MQTT panel
        MQTT_TOPIC_W   = Math.min(60, W / 3);
        MQTT_PAYLOAD_W = W - 1 - 10 - 1 - MQTT_TOPIC_W - 1;

        // Log panel
        LOG_MSG_W = W - 1 - 10 - 1 - 7 - 1 - LOG_SOURCE_W - 1;
    }

    private static final String DENOME_PATH       = "/home/pi/Teleonome/Teleonome.denome";
    private static final String JSON_OUTPUT_PATH  = "/home/pi/Teleonome/memory_status.json";
    private static final int    LOG_PORT          = 4712;
    private static final String MQTT_HOST         = "127.0.0.1";
    private static final int    MQTT_PORT         = 1883;
    private static final int    MAX_LOG_ENTRIES   = 200;
    private static final int    MAX_MQTT_ENTRIES  = 200;
    private static final int    LOG_ROWS          = 8;
    private static final int    MQTT_ROWS         = 5;

    // =========================================================
    // Build number — replaced by maven-replacer-plugin at build time
    // =========================================================
    private static String buildNumber="07/06/2026 17:04";

    // =========================================================
    // Data models
    // =========================================================
    private static class LogEntry {
        final String ts, level, source, fullName, message;
        LogEntry(String ts, String lv, String src, String fn, String msg) {
            this.ts=ts; level=lv; source=src; fullName=fn; message=msg;
        }
    }

    private static class MqttEntry {
        final String ts, topic, payload;
        MqttEntry(String ts, String t, String p) { this.ts=ts; topic=t; payload=p; }
    }

    // =========================================================
    // Shared state
    // =========================================================
    private final LinkedList<LogEntry>  recentLogs  = new LinkedList<>();
    private final LinkedList<MqttEntry> recentMqtt  = new LinkedList<>();

    // Log section filters
    private volatile boolean hideLog    = false;
    private volatile String  logFilter  = null;  // null = all

    // MQTT section filters
    private volatile boolean hideMqtt   = false;
    private volatile String  mqttFilter = null;  // null = all

    // Prompt state
    private volatile String currentInput = "";
    private volatile String suggestion   = "";

    // Render trigger — set by input thread; cleared by render thread
    private volatile boolean renderNeeded    = false;
    private volatile boolean showCommandList = false;

    // Cached stats so prompt keystrokes can trigger a fast re-render
    private volatile Map<String, Integer> lastStats       = new HashMap<>();
    private volatile Map<String, Long>    lastUpdateCache = new HashMap<>();

    // =========================================================
    // Command table — used for autocomplete and help display
    // =========================================================
    private static final String[][] COMMANDS = {
        { "listcommands",         "Show this command reference"              },
        { "show all",             "Remove log filter, show all entries"      },
        { "show ",                "Filter logs to logger names containing X" },
        { "hide all",             "Collapse the log section"                 },
        { "mqtt show",            "Expand the MQTT section"                  },
        { "mqtt hide",            "Collapse the MQTT section"                },
        { "mqtt filter ",         "Filter MQTT to topics containing X"       },
        { "mqtt clear",           "Remove MQTT topic filter"                 },
        { "kill heart",           "Kill Heart.jar"                           },
        { "kill hypothalamus",    "Kill Hypothalamus.jar"                    },
        { "kill hippocampus",     "Kill Hippocampus.jar"                     },
        { "kill medula",          "Kill Medula.jar"                          },
        { "kill tomcat",          "Kill Tomcat"                              },
        { "kill cerebellum",      "Kill Cerebellum.jar"                      },
        { "killhe",               "Kill Heart.jar"                           },
        { "killhy",               "Kill Hypothalamus.jar"                    },
        { "killhi",               "Kill Hippocampus.jar"                     },
        { "killmd",               "Kill Medula.jar"                          },
        { "killtc",               "Kill Tomcat"                              },
        { "killce",               "Kill Cerebellum.jar"                      },
    };

    // =========================================================
    // Entry point
    // =========================================================
    public static void main(String[] args) {
        Thalamus t = new Thalamus();
        t.initRawMode();
        t.startLogServer();
        t.startMqttSubscriber();
        t.startInputThread();
        t.startRelay();
    }

    // =========================================================
    // Main relay loop
    // =========================================================
    public void startRelay() {
        while (true) {
            try {
                lastStats       = getMemoryUsage();
                lastUpdateCache = getLastUpdates();
                renderDashboard(lastStats);
                writeJson(lastStats);
                renderNeeded = false;

                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    if (renderNeeded) {
                        renderDashboard(lastStats);
                        renderNeeded = false;
                    }
                    Thread.sleep(80);
                }
            } catch (Exception ignored) {}
        }
    }

    // =========================================================
    // Terminal raw-mode setup
    // =========================================================
    private void initRawMode() {
        try {
            // -icanon: disable line buffering (chars come immediately)
            // -echo:   disable kernel echo (we draw it ourselves)
            // min 1 time 0: read returns as soon as 1 byte is available
            Runtime.getRuntime()
                .exec(new String[]{"sh", "-c", "stty -icanon -echo min 1 time 0 </dev/tty"})
                .waitFor();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", "stty sane </dev/tty"})
                        .waitFor();
                } catch (Exception ignored) {}
            }));
        } catch (Exception ignored) {}
    }

    // =========================================================
    // Input thread — character-by-character with autocomplete
    // =========================================================
    private void startInputThread() {
        Thread t = new Thread(() -> {
            try {
                InputStream tty;
                try { tty = new FileInputStream("/dev/tty"); }
                catch (Exception e) { tty = System.in; }

                StringBuilder buf = new StringBuilder();
                while (true) {
                    int ch = tty.read();
                    if (ch == -1) break;

                    if (ch == 9) {                            // Tab → accept suggestion
                        String sug = suggestion;
                        if (!sug.isEmpty()) {
                            buf.setLength(0);
                            buf.append(sug);
                            currentInput = sug;
                            suggestion   = "";
                        }
                    } else if (ch == 13 || ch == 10) {       // Enter → submit
                        String cmd = buf.toString().trim();
                        buf.setLength(0);
                        currentInput = "";
                        suggestion   = "";
                        if (cmd.isEmpty()) { showCommandList = false; }
                        else handleCommand(cmd);
                    } else if (ch == 127 || ch == 8) {       // Backspace
                        if (buf.length() > 0) buf.deleteCharAt(buf.length() - 1);
                        currentInput = buf.toString();
                        suggestion   = computeSuggestion(currentInput);
                    } else if (ch == 21) {                   // Ctrl+U → clear line
                        buf.setLength(0);
                        currentInput = "";
                        suggestion   = "";
                    } else if (ch >= 32 && ch < 127) {       // printable character
                        buf.append((char) ch);
                        currentInput = buf.toString();
                        suggestion   = computeSuggestion(currentInput);
                    } else {
                        continue; // ignore control sequences (arrow keys etc.)
                    }
                    renderNeeded = true;
                }
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    /** Returns the first command that starts with input (case-insensitive), or "". */
    private String computeSuggestion(String input) {
        if (input.isEmpty()) return "";
        String lo = input.toLowerCase();
        for (String[] cmd : COMMANDS) {
            if (cmd[0].toLowerCase().startsWith(lo) && !cmd[0].equalsIgnoreCase(input)) {
                return cmd[0];
            }
        }
        return "";
    }

    // =========================================================
    // Command handling
    // =========================================================
    private void handleCommand(String cmd) {
        String lo = cmd.toLowerCase().trim();
        showCommandList = false; // any command dismisses the list view

        // --- Help ---
        if      (lo.equals("listcommands"))         { showCommandList = true; return; }

        // --- Log section ---
        else if (lo.equals("show all"))             { hideLog = false; logFilter = null; }
        else if (lo.equals("hide all"))             { hideLog = true;  logFilter = null; }
        else if (lo.startsWith("show "))            { hideLog = false; logFilter = cmd.substring(5).trim(); }

        // --- MQTT section ---
        else if (lo.equals("mqtt show"))            { hideMqtt = false; }
        else if (lo.equals("mqtt hide"))            { hideMqtt = true;  }
        else if (lo.equals("mqtt clear"))           { mqttFilter = null; }
        else if (lo.startsWith("mqtt filter "))     { hideMqtt = false; mqttFilter = cmd.substring(12).trim(); }

        // --- Kill commands (long form) ---
        else if (lo.equals("kill heart"))           { killProcess("Heart.jar"); }
        else if (lo.equals("kill hypothalamus"))    { killProcess("Hypothalamus.jar"); }
        else if (lo.equals("kill hippocampus"))     { killProcess("Hippocampus.jar"); }
        else if (lo.equals("kill medula"))          { killProcess("Medula.jar"); }
        else if (lo.equals("kill tomcat"))          { killProcess("tomcat"); }
        else if (lo.equals("kill cerebellum"))      { killProcess("Cerebellum.jar"); }

        // --- Kill commands (short codes) ---
        else if (lo.equals("killhe"))               { killProcess("Heart.jar"); }
        else if (lo.equals("killhy"))               { killProcess("Hypothalamus.jar"); }
        else if (lo.equals("killhi"))               { killProcess("Hippocampus.jar"); }
        else if (lo.equals("killmd"))               { killProcess("Medula.jar"); }
        else if (lo.equals("killtc"))               { killProcess("tomcat"); }
        else if (lo.equals("killce"))               { killProcess("Cerebellum.jar"); }
    }

    private void killProcess(String name) {
        try {
            if (name.equals("tomcat")) {
                Runtime.getRuntime().exec(new String[]{"pkill", "-f", "catalina.startup.Bootstrap"});
            } else {
                Runtime.getRuntime().exec(new String[]{"pkill", "-f", name});
            }
        } catch (Exception ignored) {}
    }

    // =========================================================
    // Log4J socket server — port 4712
    // =========================================================
    private void startLogServer() {
        Thread t = new Thread(() -> {
            try (ServerSocket srv = new ServerSocket(LOG_PORT)) {
                while (true) {
                    Socket s = srv.accept();
                    Thread r = new Thread(new LogReceiver(s));
                    r.setDaemon(true);
                    r.start();
                }
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    private class LogReceiver implements Runnable {
        private final Socket socket;
        private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        LogReceiver(Socket s) { socket = s; }

        public void run() {
            try {
                ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(socket.getInputStream()));
                while (true) {
                    LoggingEvent event = (LoggingEvent) ois.readObject();
                    String full = event.getLoggerName();
                    String src  = shortName(full);
                    String msg  = event.getRenderedMessage();
                    String ts   = fmt.format(new Date(event.timeStamp));
                    addLog(new LogEntry(ts, event.getLevel().toString(), src, full,
                                        msg != null ? msg : ""));
                    renderNeeded = true;
                }
            } catch (java.io.EOFException | java.net.SocketException ignored) {
            } catch (Exception ignored) {}
        }

        private String shortName(String n) {
            if (n == null) return "?";
            String[] p = n.split("\\.");
            return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : n;
        }
    }

    private void addLog(LogEntry e) {
        synchronized (recentLogs) {
            recentLogs.addLast(e);
            if (recentLogs.size() > MAX_LOG_ENTRIES) recentLogs.removeFirst();
        }
    }

    // =========================================================
    // MQTT subscriber — minimal MQTT 3.1.1 over raw sockets
    // (no dependency; reconnects automatically)
    // =========================================================
    private void startMqttSubscriber() {
        Thread t = new Thread(() -> {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
            while (true) {
                try {
                    Socket sock = new Socket(MQTT_HOST, MQTT_PORT);
                    sock.setSoTimeout(90_000); // 90s read timeout
                    DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                    InputStream      in  = new BufferedInputStream(sock.getInputStream());

                    // CONNECT
                    mqttConnect(out, "Thalamus-Monitor");
                    // CONNACK
                    in.read(); in.read(); in.read();          // type, len, sessionPresent
                    if (in.read() != 0) { sock.close(); continue; } // bad return code

                    // SUBSCRIBE "#" QoS 0
                    mqttSubscribe(out, 1, "#");
                    // SUBACK — drain it
                    in.read(); // 0x90
                    int subackLen = mqttReadRemainingLength(in);
                    for (int i = 0; i < subackLen; i++) in.read();

                    while (!sock.isClosed()) {
                        int first = in.read();
                        if (first == -1) break;
                        int type      = (first >> 4) & 0x0F;
                        int flags     = first & 0x0F;
                        int remaining = mqttReadRemainingLength(in);

                        if (type == 3) {                     // PUBLISH
                            int qos      = (flags >> 1) & 0x03;
                            int topicLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                            remaining -= (2 + topicLen);
                            String topic = new String(mqttRead(in, topicLen), "UTF-8");
                            if (qos > 0) {                   // packet id
                                int ph = in.read(), pl = in.read();
                                remaining -= 2;
                                if (qos == 1) {              // send PUBACK
                                    out.write(new byte[]{0x40, 0x02, (byte)ph, (byte)pl});
                                    out.flush();
                                }
                            }
                            String payload = new String(mqttRead(in, remaining), "UTF-8");
                            addMqtt(new MqttEntry(fmt.format(new Date()), topic, payload));
                            renderNeeded = true;
                        } else {
                            mqttRead(in, remaining);         // drain unknown packet types
                        }
                    }
                    sock.close();
                } catch (SocketTimeoutException ignored) {
                    // reconnect — broker was silent for 90s
                } catch (Exception ignored) {}
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void mqttConnect(DataOutputStream out, String clientId) throws IOException {
        byte[] id = clientId.getBytes("UTF-8");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x10);
        mqttWriteRemainingLength(buf, 10 + 2 + id.length);
        buf.write(new byte[]{0x00, 0x04, 'M', 'Q', 'T', 'T'}); // protocol name
        buf.write(0x04);                                         // level 4
        buf.write(0x02);                                         // clean session
        buf.write(new byte[]{0x00, 0x00});                       // keepalive = 0
        buf.write((id.length >> 8) & 0xFF);
        buf.write(id.length & 0xFF);
        buf.write(id);
        out.write(buf.toByteArray());
        out.flush();
    }

    private void mqttSubscribe(DataOutputStream out, int packetId, String topic) throws IOException {
        byte[] tb = topic.getBytes("UTF-8");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x82);
        mqttWriteRemainingLength(buf, 2 + 2 + tb.length + 1);
        buf.write((packetId >> 8) & 0xFF);
        buf.write(packetId & 0xFF);
        buf.write((tb.length >> 8) & 0xFF);
        buf.write(tb.length & 0xFF);
        buf.write(tb);
        buf.write(0x00); // QoS 0
        out.write(buf.toByteArray());
        out.flush();
    }

    private int mqttReadRemainingLength(InputStream in) throws IOException {
        int mult = 1, val = 0, b;
        do { b = in.read(); if (b == -1) throw new IOException("EOF"); val += (b & 0x7F) * mult; mult *= 128; }
        while ((b & 0x80) != 0);
        return val;
    }

    private void mqttWriteRemainingLength(OutputStream out, int len) throws IOException {
        do { int b = len % 128; len /= 128; if (len > 0) b |= 128; out.write(b); } while (len > 0);
    }

    private byte[] mqttRead(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n]; int done = 0;
        while (done < n) { int r = in.read(buf, done, n - done); if (r == -1) throw new IOException("EOF"); done += r; }
        return buf;
    }

    private void addMqtt(MqttEntry e) {
        synchronized (recentMqtt) {
            recentMqtt.addLast(e);
            if (recentMqtt.size() > MAX_MQTT_ENTRIES) recentMqtt.removeFirst();
        }
    }

    // =========================================================
    // Memory polling
    // =========================================================

    // =========================================================
    // Last-update timestamps
    // =========================================================

    /** Returns a map of organ → epoch-millis of its last status update. */
    private Map<String, Long> getLastUpdates() {
        Map<String, Long> m = new HashMap<>();
        String base = "/home/pi/Teleonome/";
        for (String jar : ORGANS) {
            long ts = 0;
            try {
                if      (jar.equals("Cerebellum.jar"))   ts = readJsonTimestamp(base + "CerebellumStatus.json");
                else if (jar.equals("Hippocampus.jar"))  ts = readJsonTimestamp(base + "HippocampusStatus.json");
                else if (jar.equals("Heart.jar"))        ts = new File(base + "heart/HeartPing.info").lastModified();
                else if (jar.equals("Hypothalamus.jar")) ts = new File(base + "Teleonome.denome").lastModified();
                else if (jar.equals("tomcat"))           ts = new File(base + "WebServerPing.info").lastModified();
                // Medula, others: no status file specified — leave ts=0
            } catch (Exception ignored) {}
            m.put(jar, ts);
        }
        return m;
    }

    /** Parse "Timestamp Milliseconds": <number> from a simple JSON status file. */
    private long readJsonTimestamp(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("Timestamp Milliseconds")) {
                    String num = line.replaceAll("[^0-9]", "");
                    if (!num.isEmpty()) return Long.parseLong(num);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** Format millisecond epoch as a right-justified LAST_W-char age string, e.g. "   42s". */
    private String formatAge(long millis) {
        if (millis <= 0) return String.format("%" + LAST_W + "s", "?");
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60)   return String.format("%"+(LAST_W-1)+"ds", s);           // "   42s"
        if (s < 3600) return String.format("%"+(LAST_W-4)+"dm%02d", s/60, s%60); // " 5m30"
        return         String.format("%"+(LAST_W-4)+"dh%02d", s/3600, (s%3600)/60); // " 1h23"
    }

    /** PID lookup via ps -ef — used as fallback and for Tomcat (which jcmd -l can't see). */
    private int getPidByJarName(String organName) {
        try {
            Process p = Runtime.getRuntime().exec("ps -ef");
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (organName.equals("tomcat")) {
                    if (line.contains("org.apache.catalina.startup.Bootstrap") &&
                        line.contains("/home/pi/Teleonome/tomcat")) {
                        return Integer.parseInt(line.trim().split("\\s+")[1]);
                    }
                } else if (line.contains(organName)) {
                    return Integer.parseInt(line.trim().split("\\s+")[1]);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private Map<String, Integer> getMemoryUsage() {
        Map<String, Integer> stats = new HashMap<>();
        for (String jar : ORGANS) {
            int pid = -1;
            try {
                // jcmd -l lists JVM processes by JAR path or main class name.
                // Tomcat appears as "org.apache.catalina.startup.Bootstrap", not "tomcat",
                // so it never matches here — getPidByJarName() is the fallback for that case.
                Process p = Runtime.getRuntime().exec("jcmd -l");
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains(jar)) { pid = Integer.parseInt(line.split(" ")[0]); break; }
                }
                // Fallback: use ps -ef (handles Tomcat and any process jcmd can't see)
                if (pid == -1) pid = getPidByJarName(jar);

                if (pid != -1) {
                    Process pm = Runtime.getRuntime().exec("ps -p " + pid + " -o rss=");
                    BufferedReader rm = new BufferedReader(new InputStreamReader(pm.getInputStream()));
                    String ml = rm.readLine();
                    if (ml != null) stats.put(jar, Integer.parseInt(ml.trim()) / 1024);
                } else {
                    stats.put(jar, 0);
                }
            } catch (Exception ignored) {}
        }
        return stats;
    }

    // =========================================================
    // Rendering — three panels + prompt
    // =========================================================
    private void renderDashboard(Map<String, Integer> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("\033[H\033[2J");  // clear screen, cursor home

        if (showCommandList) {
            renderCommandList(sb);
        } else {
            renderMemory(sb, stats);
            renderMqtt(sb);
            renderLogs(sb);
        }
        renderPrompt(sb);

        System.out.print(sb);
        System.out.flush();
    }

    // ----- Command list overlay -----
    private void renderCommandList(StringBuilder sb) {
        sb.append(SEP_D).append('\n');
        sb.append(" THALAMUS — COMMAND REFERENCE\n");
        sb.append(SEP_D).append('\n');
        sb.append(String.format(" %-36s  %s\n", "COMMAND", "DESCRIPTION"));
        sb.append(SEP_S).append('\n');
        for (String[] cmd : COMMANDS) {
            sb.append(String.format(" \033[36m%-36s\033[0m  %s\n", cmd[0], cmd[1]));
        }
        sb.append(SEP_D).append('\n');
        sb.append(" Press Enter to return to the dashboard\n");
    }

    // ----- Panel 1: Memory (left) + Active Sources (right) -----
    private void renderMemory(StringBuilder sb, Map<String, Integer> stats) {
        sb.append(SEP_D).append('\n');
        sb.append(String.format(" THALAMUS [%s]   %s\n", buildNumber, new SimpleDateFormat("HH:mm:ss").format(new Date())));
        sb.append(SEP_D).append('\n');

        File f = new File(DENOME_PATH);
        if (f.exists()) {
            long age = (System.currentTimeMillis() - f.lastModified()) / 1000;
            String c = age < 60 ? "\033[32m" : "\033[31m";
            sb.append(String.format(" Denome: %s%ds old\033[0m", c, age));
        } else {
            sb.append(" Denome: \033[31mFILE MISSING\033[0m");
        }
        List<String> sources = getActiveSources();
        sb.append(String.format("%"+(LEFT_COL-24)+"s │  \033[32mACTIVE SOURCES\033[0m (%d)\n", "", sources.size()));

        // header: ORGAN RSS HEALTH % AGO │ DEVICE/SOURCE
        sb.append(String.format(" %-16s %-7s %-"+(BAR_W+2)+"s %3s %-"+LAST_W+"s │  %s\n",
            "ORGAN", "RSS", "HEALTH", "%", "AGO", "DEVICE / SOURCE"));
        sb.append(SEP_S).append('\n');

        int rows = Math.max(ORGANS.length, sources.size());
        for (int row = 0; row < rows; row++) {
            // --- left: organ row ---
            if (row < ORGANS.length) {
                String jar  = ORGANS[row];
                String name = jar.replace(".jar", "");
                int used    = stats.getOrDefault(jar, 0);
                int limit   = name.equals("Hippocampus") ? 384 : 128;
                String age  = formatAge(lastUpdateCache.getOrDefault(jar, 0L));
                if (used == 0) {
                    // offline: pad bar slot with [OFFLINE] left-justified, then show age
                    sb.append(String.format(" %-16s %-7s \033[31m%-"+(BAR_W+2)+"s\033[0m     %s",
                        name, "---", "[OFFLINE]", age));
                } else {
                    double pct = (double) used / limit;
                    String c = pct > 0.9 ? "\033[31m" : pct > 0.7 ? "\033[33m" : "\033[32m";
                    StringBuilder bar = new StringBuilder("[");
                    int filled = (int) (BAR_W * Math.min(pct, 1.0));
                    for (int i = 0; i < BAR_W; i++) bar.append(i < filled ? "█" : "░");
                    bar.append("]");
                    sb.append(String.format(" %-16s %-7s %s%s\033[0m %3d%% %s",
                        name, used + "MB", c, bar, (int)(pct * 100), age));
                }
            } else {
                sb.append(String.format("%-"+LEFT_COL+"s", ""));
            }

            // --- separator + right: source row ---
            String src = row < sources.size() ? sources.get(row) : "";
            sb.append(String.format(" │  %-"+RIGHT_COL+"s\n", trunc(src, RIGHT_COL)));
        }
    }

    /** Most recently active unique sources from logs + MQTT topic roots. */
    private List<String> getActiveSources() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        synchronized (recentLogs) {
            java.util.Iterator<LogEntry> it = recentLogs.descendingIterator();
            while (it.hasNext() && seen.size() < 30) seen.add(it.next().source);
        }
        synchronized (recentMqtt) {
            java.util.Iterator<MqttEntry> it = recentMqtt.descendingIterator();
            while (it.hasNext() && seen.size() < 50) {
                String topic = it.next().topic;
                String root  = topic.contains("/") ? topic.substring(0, topic.indexOf('/')) : topic;
                if (!root.isEmpty()) seen.add(root);
            }
        }
        return new ArrayList<>(seen);
    }

    // ----- Panel 2: MQTT -----
    private void renderMqtt(StringBuilder sb) {
        sb.append(SEP_D).append('\n');
        if (hideMqtt) {
            sb.append(" MQTT [\033[31mHIDDEN\033[0m]  type: mqtt show\n");
            for (int i = 0; i < MQTT_ROWS + 2; i++) sb.append("\n");
            return;
        }
        List<MqttEntry> visible = getVisibleMqtt();
        String fl = mqttFilter != null
            ? "\033[33mfilter: " + mqttFilter + "\033[0m"
            : "\033[32mall\033[0m";
        sb.append(String.format(" MQTT [%s]  %d msgs\n", fl, visible.size()));
        sb.append(String.format(" %-10s %-"+MQTT_TOPIC_W+"s %s\n", "TIME", "TOPIC", "PAYLOAD"));
        sb.append(SEP_S).append('\n');

        int start = Math.max(0, visible.size() - MQTT_ROWS);
        int shown = 0;
        for (int i = start; i < visible.size(); i++) {
            MqttEntry e = visible.get(i);
            String topic   = trunc(e.topic,   MQTT_TOPIC_W);
            String payload = trunc(e.payload, MQTT_PAYLOAD_W);
            sb.append(String.format(" %-10s \033[36m%-"+MQTT_TOPIC_W+"s\033[0m %s\n",
                e.ts, topic, payload));
            shown++;
        }
        for (int i = shown; i < MQTT_ROWS; i++) sb.append("\n");
    }

    private List<MqttEntry> getVisibleMqtt() {
        List<MqttEntry> out = new ArrayList<>();
        synchronized (recentMqtt) {
            for (MqttEntry e : recentMqtt) {
                if (mqttFilter == null
                    || e.topic.toLowerCase().contains(mqttFilter.toLowerCase()))
                    out.add(e);
            }
        }
        return out;
    }

    // ----- Panel 3: Log4J -----
    private void renderLogs(StringBuilder sb) {
        sb.append(SEP_D).append('\n');
        if (hideLog) {
            sb.append(" LOGS [\033[31mHIDDEN\033[0m]  type: show all\n");
            for (int i = 0; i < LOG_ROWS + 2; i++) sb.append("\n");
            return;
        }
        List<LogEntry> visible = getVisibleLogs();
        String fl = logFilter != null
            ? "\033[33mfilter: " + logFilter + "\033[0m"
            : "\033[32mall\033[0m";
        sb.append(String.format(" LOGS [%s]\n", fl));
        sb.append(String.format(" %-10s %-7s %-"+LOG_SOURCE_W+"s %s\n", "TIME", "LEVEL", "SOURCE", "MESSAGE"));
        sb.append(SEP_S).append('\n');

        int start = Math.max(0, visible.size() - LOG_ROWS);
        int shown = 0;
        for (int i = start; i < visible.size(); i++) {
            LogEntry e = visible.get(i);
            String c   = levelColor(e.level);
            String msg = trunc(e.message, LOG_MSG_W);
            sb.append(String.format(" %-10s %s%-7s\033[0m %-"+LOG_SOURCE_W+"s %s\n",
                e.ts, c, e.level, trunc(e.source, LOG_SOURCE_W), msg));
            shown++;
        }
        for (int i = shown; i < LOG_ROWS; i++) sb.append("\n");
    }

    private List<LogEntry> getVisibleLogs() {
        List<LogEntry> out = new ArrayList<>();
        if (hideLog) return out;
        synchronized (recentLogs) {
            for (LogEntry e : recentLogs) {
                if (logFilter == null
                    || e.fullName.toLowerCase().contains(logFilter.toLowerCase())
                    || e.source.toLowerCase().contains(logFilter.toLowerCase()))
                    out.add(e);
            }
        }
        return out;
    }

    // ----- Prompt with autocomplete -----
    private void renderPrompt(StringBuilder sb) {
        sb.append(SEP_D).append('\n');
        sb.append(" show <name>  show all  hide all  │  mqtt filter <name>  mqtt clear  mqtt hide  mqtt show  │  listcommands\n");
        sb.append(" kill heart/hypothalamus/hippocampus/medula/cerebellum/tomcat  │  killhe  killhy  killhi  killmd  killce  killtc\n");
        sb.append(SEP_S).append('\n');

        String input = currentInput;
        String sug   = suggestion;
        if (!sug.isEmpty() && sug.length() > input.length()) {
            // typed part normal, untyped remainder dimmed
            sb.append(" > ").append(input)
              .append("\033[2m").append(sug.substring(input.length())).append("\033[0m");
        } else {
            sb.append(" > ").append(input);
        }
    }

    // =========================================================
    // Helpers
    // =========================================================
    private String levelColor(String level) {
        if ("ERROR".equals(level) || "FATAL".equals(level)) return "\033[31m";
        if ("WARN".equals(level))  return "\033[33m";
        if ("INFO".equals(level))  return "\033[36m";
        return "\033[0m";
    }

    private String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // =========================================================
    // JSON output for web consumer
    // =========================================================
    private void writeJson(Map<String, Integer> stats) {
        try {
            StringBuilder json = new StringBuilder("{\"timestamp\":")
                .append(System.currentTimeMillis()).append(",\"organs\":[");
            int n = 0;
            for (Map.Entry<String, Integer> e : stats.entrySet()) {
                if (n++ > 0) json.append(",");
                json.append("{\"name\":\"").append(e.getKey().replace(".jar",""))
                    .append("\",\"used\":").append(e.getValue()).append("}");
            }
            json.append("]}");
            try (FileWriter fw = new FileWriter(JSON_OUTPUT_PATH)) { fw.write(json.toString()); }
        } catch (Exception ignored) {}
    }
}
