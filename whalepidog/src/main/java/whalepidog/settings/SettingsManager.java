package whalepidog.settings;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import org.json.JSONObject;

/**
 * Reads and writes {@link WhalePIDogSettings} to / from a JSON file.
 *
 * <p>Example JSON:
 * <pre>
 * {
 *   "pamguardJar"            : "/opt/pamguard/pamguard.jar",
 *   "psfxFile"               : "/data/config/myConfig.psfx",
 *   "wavFolder"              : "/data/recordings",
 *   "database"               : "/data/pamguard.sqlite3",
 *   "libFolder"              : "/opt/pamguard/lib64",
 *   "jre"                    : "java",
 *   "mxMemory"               : 4096,
 *   "msMemory"               : 2048,
 *   "otherVMOptions"         : "",
 *   "otherOptions"           : "",
 *   "deploy"                 : true,
 *   "udpPort"                : 8000,
 *   "checkIntervalSeconds"   : 30,
 *   "summaryIntervalSeconds" : 5,
 *   "startWaitSeconds"       : 10,
 *   "workingFolder"          : ""
 * }
 * </pre>
 */
public class SettingsManager {

    /**
     * Load settings from the given JSON file.
     *
     * @param file path to the JSON settings file
     * @return populated {@link WhalePIDogSettings}, or {@code null} on error
     */
    public static WhalePIDogSettings load(File file) {
        if (!file.exists()) {
            System.err.println("Settings file not found: " + file.getAbsolutePath());
            return null;
        }

        String json = readFile(file);
        if (json == null) return null;

        try {
            JSONObject jo = new JSONObject(json);
            WhalePIDogSettings s = new WhalePIDogSettings();

            if (jo.has("pamguardJar")            && !jo.isNull("pamguardJar"))            s.setPamguardJar(jo.getString("pamguardJar"));
            if (jo.has("psfxFile")               && !jo.isNull("psfxFile"))               s.setPsfxFile(jo.getString("psfxFile"));
            if (jo.has("wavFolder")              && !jo.isNull("wavFolder"))              s.setWavFolder(jo.getString("wavFolder"));
            if (jo.has("database")               && !jo.isNull("database"))               s.setDatabase(jo.getString("database"));
            if (jo.has("libFolder")              && !jo.isNull("libFolder"))              s.setLibFolder(jo.getString("libFolder"));
            if (jo.has("jre")                    && !jo.isNull("jre"))                    s.setJre(jo.getString("jre"));
            if (jo.has("mxMemory"))                                                       s.setMxMemory(jo.getInt("mxMemory"));
            if (jo.has("msMemory"))                                                       s.setMsMemory(jo.getInt("msMemory"));
            if (jo.has("otherVMOptions")         && !jo.isNull("otherVMOptions"))         s.setOtherVMOptions(jo.getString("otherVMOptions"));
            if (jo.has("otherOptions")           && !jo.isNull("otherOptions"))           s.setOtherOptions(jo.getString("otherOptions"));
            if (jo.has("deploy"))                                                         s.setDeploy(jo.getBoolean("deploy"));
            if (jo.has("noGui"))                                                          s.setNoGui(jo.getBoolean("noGui"));
            if (jo.has("udpPort"))                                                        s.setUdpPort(jo.getInt("udpPort"));
            if (jo.has("checkIntervalSeconds"))                                           s.setCheckIntervalSeconds(jo.getInt("checkIntervalSeconds"));
            if (jo.has("summaryIntervalSeconds"))                                         s.setSummaryIntervalSeconds(jo.getInt("summaryIntervalSeconds"));
            if (jo.has("startWaitSeconds"))                                               s.setStartWaitSeconds(jo.getInt("startWaitSeconds"));
            if (jo.has("workingFolder")          && !jo.isNull("workingFolder"))          s.setWorkingFolder(jo.getString("workingFolder"));

            return s;

        } catch (Exception e) {
            System.err.println("Failed to parse settings file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Save settings to the given JSON file (pretty-printed, 4-space indent).
     *
     * @param file     destination file
     * @param settings settings to write
     * @return {@code true} on success
     */
    public static boolean save(File file, WhalePIDogSettings settings) {
        JSONObject jo = new JSONObject();
        jo.put("pamguardJar",            settings.getPamguardJar());
        jo.put("psfxFile",               settings.getPsfxFile());
        jo.put("wavFolder",              settings.getWavFolder());
        jo.put("database",               settings.getDatabase());
        jo.put("libFolder",              settings.getLibFolder());
        jo.put("jre",                    settings.getJre());
        jo.put("mxMemory",               settings.getMxMemory());
        jo.put("msMemory",               settings.getMsMemory());
        jo.put("otherVMOptions",         settings.getOtherVMOptions());
        jo.put("otherOptions",           settings.getOtherOptions());
        jo.put("deploy",                 settings.isDeploy());
        jo.put("noGui",                  settings.isNoGui());
        jo.put("udpPort",                settings.getUdpPort());
        jo.put("checkIntervalSeconds",   settings.getCheckIntervalSeconds());
        jo.put("summaryIntervalSeconds", settings.getSummaryIntervalSeconds());
        jo.put("startWaitSeconds",       settings.getStartWaitSeconds());
        jo.put("workingFolder",          settings.getWorkingFolder());

        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(jo.toString(4));
            }
            return true;
        } catch (IOException e) {
            System.err.println("Failed to write settings: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write a template settings JSON to the given path.  Useful for first-run bootstrapping.
     *
     * @param file destination file
     */
    public static void writeTemplate(File file) {
        WhalePIDogSettings defaults = new WhalePIDogSettings();
        defaults.setPamguardJar("/home/jdjm/Desktop/pamguard_pi5/Pamguard-2.02.18.jar");
        defaults.setPsfxFile("/home/jdjm/Desktop/pamguard_pi5/pamguard_pi5.psfx");
        defaults.setWavFolder("/home/jdjm/Desktop/pamguard_pi5/PAMRecordings");
        defaults.setDatabase("/home/jdjm/Desktop/pamguard_pi5/dogdatabase.sqlite3");
        save(file, defaults);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String readFile(File file) {
        try (FileReader fr = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = fr.read()) != -1) sb.append((char) ch);
            return sb.toString();
        } catch (IOException e) {
            System.err.println("Cannot read file: " + e.getMessage());
            return null;
        }
    }
}
