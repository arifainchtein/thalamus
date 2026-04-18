package com.teleonome.thalamus;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * THALAMUS: The Live Sensory Relay Station
 * Monitors the RAM of all Teleonome Organs and the Denome file freshness.
 */
public class Thalamus {

// Updated list to include Tomcat
    private static final String[] ORGANS = {
        "Heart.jar", 
        "Hypothalamus.jar", 
        "Hippocampus.jar",
        "Medulla.jar",
        "tomcat" // We will use this as a keyword
    };

    private static final String DENOME_PATH = "/home/pi/Teleonome/Teleonome.denome";
    private static final String JSON_OUTPUT_PATH = "/home/pi/Teleonome/memory_status.json";

    public static void main(String[] args) {
        Thalamus t = new Thalamus();
        t.startRelay();
    }

private int getPidByJarName(String organName) {
        try {
            // jcmd -l shows the main class or JAR for Java processes
            Process p = Runtime.getRuntime().exec("jcmd -l");
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // Check if it's a standard JAR organ
                if (line.contains(organName)) {
                    return Integer.parseInt(line.split(" ")[0]);
                }
                // Special check for Tomcat
                if (organName.equals("tomcat") && line.contains("org.apache.catalina.startup.Bootstrap")) {
                    return Integer.parseInt(line.split(" ")[0]);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }
    public void startRelay() {
        while (true) {
            try {
                // 1. Collect Data
                Map<String, Integer> stats = getMemoryUsage();
                
                // 2. Visual Output (ANSI Terminal)
                renderDashboard(stats);
                
                // 3. Save to JSON (For Web)
                writeJson(stats);
                
                // Refresh every 5 seconds
                Thread.sleep(5000); 
            } catch (Exception e) {
                System.out.println("\nRelay Error: " + e.getMessage());
            }
        }
    }

    private Map<String, Integer> getMemoryUsage() {
        Map<String, Integer> stats = new HashMap<>();
        for (String jar : ORGANS) {
            int pid = -1;
            try {
                // Find PID of Java processes matching the JAR name
                Process p = Runtime.getRuntime().exec("jcmd -l");
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains(jar)) {
                        pid = Integer.parseInt(line.split(" ")[0]);
                        break;
                    }
                }
                
                if (pid != -1) {
                    // Get Resident Set Size (RSS) in KB via ps
                    Process pMem = Runtime.getRuntime().exec("ps -p " + pid + " -o rss=");
                    BufferedReader rMem = new BufferedReader(new InputStreamReader(pMem.getInputStream()));
                    String memLine = rMem.readLine();
                    if (memLine != null) {
                        stats.put(jar, Integer.parseInt(memLine.trim()) / 1024); // Convert to MB
                    }
                } else {
                    stats.put(jar, 0); // Offline
                }
            } catch (Exception ignored) {}
        }
        return stats;
    }

    private void renderDashboard(Map<String, Integer> stats) {
        // Clear screen and move cursor to top-left
        System.out.print("\033[H\033[2J");
        System.out.flush();

        System.out.println("=====================================================");
        System.out.println("          THALAMUS: LIVE SENSORY RELAY               ");
        System.out.println("          " + new Date());
        System.out.println("=====================================================");

        // Denome check
        File f = new File(DENOME_PATH);
        if (f.exists()) {
            long age = (System.currentTimeMillis() - f.lastModified()) / 1000;
            String color = (age < 60) ? "\033[32m" : "\033[31m";
            System.out.println("Denome Status: " + color + age + "s old\033[0m");
        } else {
            System.out.println("Denome Status: \033[31mFILE MISSING\033[0m");
        }

        System.out.println("-----------------------------------------------------");
        System.out.printf("%-15s %-12s %-20s\n", "ORGAN", "RSS RAM", "HEALTH BAR");

        for (String jar : ORGANS) {
            String name = jar.replace(".jar", "");
            int used = stats.getOrDefault(jar, 0);
            int limit = name.equals("Hippocampus") ? 384 : 128; // Specific limit for the big one

            if (used == 0) {
                System.out.printf("%-15s %-12s \033[31m[OFFLINE]\033[0m\n", name, "---");
                continue;
            }

            double pct = (double) used / limit;
            String color = (pct > 0.9) ? "\033[31m" : (pct > 0.7) ? "\033[33m" : "\033[32m";
            
            // Build simple bar
            StringBuilder bar = new StringBuilder("[");
            int filled = (int) (20 * Math.min(pct, 1.0));
            for (int i = 0; i < 20; i++) bar.append(i < filled ? "#" : "-");
            bar.append("]");

            System.out.printf("%-15s %-12s %s%s\033[0m %d%%\n", 
                              name, used + "MB", color, bar, (int)(pct * 100));
        }
        System.out.println("=====================================================");
        System.out.println(" Monitoring... (Ctrl+C to stop)");
    }

    private void writeJson(Map<String, Integer> stats) {
        try {
            // Minimal manual JSON to avoid needing heavy libraries if not present
            StringBuilder json = new StringBuilder("{\"timestamp\":").append(System.currentTimeMillis()).append(",\"organs\":[");
            int count = 0;
            for (Map.Entry<String, Integer> e : stats.entrySet()) {
                if (count > 0) json.append(",");
                json.append("{\"name\":\"").append(e.getKey().replace(".jar", "")).append("\",");
                json.append("\"used\":").append(e.getValue()).append("}");
                count++;
            }
            json.append("]}");
            
            try (FileWriter fw = new FileWriter(JSON_OUTPUT_PATH)) {
                fw.write(json.toString());
            }
        } catch (Exception ignored) {}
    }
}
