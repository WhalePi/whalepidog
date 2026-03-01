package whalepidog.ui;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the PAMGuard UDP {@code summary} response into typed data objects.
 *
 * <p>The summary is a sequence of lines, each wrapping an XML fragment:
 * <pre>
 *   &lt;Data Acquisition&gt;Sound Acquisition:&lt;RawDataSummary&gt;...&lt;/RawDataSummary&gt;&lt;\Data Acquisition&gt;
 *   &lt;NMEA Data&gt;NMEA Data Collection:$GP...&lt;\NMEA Data&gt;
 *   ...
 * </pre>
 *
 * <p>The closing tag uses a backslash ({@code &lt;\Tag&gt;}) which is non-standard XML,
 * so we fix it up before parsing. Sections that are absent (PAMGuard configuration
 * differences) are simply null in the result — the UI must handle that gracefully.
 */
public class SummaryParser {

    // ── Result container ──────────────────────────────────────────────────────

    public static class ParsedSummary {
        public SoundAcquisitionData  soundAcquisition;  // may be null
        public SoundRecorderData     soundRecorder;     // may be null
        public GpsData               gps;               // may be null
        public NmeaData              nmea;              // may be null
        public AnalogSensorsData     analogSensors;     // may be null
        public PiTemperatureData     piTemperature;     // may be null
        /** Any sections we didn't recognise, stored as raw key→value pairs. */
        public final List<RawSection> unknownSections = new ArrayList<>();
    }

    // ── Per-section data types ────────────────────────────────────────────────

    public static class ChannelLevel {
        public final int    index;
        public final double mean;
        public final double peakDb;
        public final double rmsDb;
        public ChannelLevel(int index, double mean, double peakDb, double rmsDb) {
            this.index = index; this.mean = mean; this.peakDb = peakDb; this.rmsDb = rmsDb;
        }
    }

    public static class SoundAcquisitionData {
        public final List<ChannelLevel> channels = new ArrayList<>();
    }

    public static class SoundRecorderData {
        public String  button;          // "start" / "stop"
        public String  state;           // "recording" / "stopped"
        public double  freeSpaceMb;     // remaining disk space in MB
        public double  fileSizeMb;      // current recording file size in MB
        // channel amplitudes (dB) – may be empty
        public final List<ChannelLevel> channelAmplitudes = new ArrayList<>();
    }

    public static class GpsData {
        public String status;
        public String timestamp;
        public double latitude;
        public double longitude;
        public double headingDeg;
    }

    public static class NmeaData {
        public String rawSentence;
    }

    public static class AnalogSensorsData {
        public long   timeMillis;
        /** Named sensor readings: name → (calVal, voltage) */
        public final List<SensorReading> readings = new ArrayList<>();
    }

    public static class SensorReading {
        public final String name;
        public final double calVal;
        public final double voltage;
        public SensorReading(String name, double calVal, double voltage) {
            this.name = name; this.calVal = calVal; this.voltage = voltage;
        }
    }

    public static class PiTemperatureData {
        public double tempCelsius;
    }

    public static class SystemDiagnosticsData {
        public long pamguardMemoryUsedMB;
        public long pamguardMemoryTotalMB;
        public long pamguardMemoryMaxMB;
        public long systemMemoryUsedMB;
        public long systemMemoryTotalMB;
        public final List<CpuCore> cpuCores = new ArrayList<>();
    }

    public static class CpuCore {
        public final int index;
        public final double usagePercent;
        public CpuCore(int index, double usagePercent) {
            this.index = index;
            this.usagePercent = usagePercent;
        }
    }

    public static class RawSection {
        public final String tag;
        public final String content;
        public RawSection(String tag, String content) { this.tag = tag; this.content = content; }
    }

    // ── Parser entry point ────────────────────────────────────────────────────

    /**
     * Parse the raw summary string returned by PAMGuard's {@code summary} UDP command.
     *
     * @param raw the full multi-line summary string
     * @return a {@link ParsedSummary}; never null, but fields may be null if absent
     */
    public static ParsedSummary parse(String raw) {
        ParsedSummary result = new ParsedSummary();
        if (raw == null || raw.isBlank()) return result;

        // Split on lines; each line is one section
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            parseLine(line, result);
        }
        return result;
    }

    /**
     * Parse the diagnostics response (direct XML, not wrapped in section tags).
     *
     * @param raw the XML response from the diagnostics command
     * @return SystemDiagnosticsData, or null if parsing fails
     */
    public static SystemDiagnosticsData parseDiagnostics(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return parseSystemDiagnosticsXml(raw);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Line-level dispatch ────────────────────────────────────────────────────

    private static void parseLine(String line, ParsedSummary result) {
        // Extract outer tag name: <Tag Name>...<\Tag Name>
        if (!line.startsWith("<")) return;

        int closeAngle = line.indexOf('>');
        if (closeAngle < 0) return;
        String tag = line.substring(1, closeAngle).trim();

        // Fix the non-standard closing tag <\Tag> → </Tag> so XML parsers accept it
        String fixed = line.replaceAll("<\\\\", "</");

        // Strip outer wrapper tag so we get at the inner content
        // e.g. <Data Acquisition>Sound Acquisition:<RawDataSummary>...</RawDataSummary></Data Acquisition>
        String inner = stripOuterTag(fixed, tag);

        // Dispatch by tag
        try {
            if (tag.equalsIgnoreCase("Data Acquisition")) {
                result.soundAcquisition = parseSoundAcquisition(inner);
            } else if (tag.equalsIgnoreCase("Sound Recorder")) {
                result.soundRecorder = parseSoundRecorder(inner);
            } else if (tag.equalsIgnoreCase("GPS Acquisition")) {
                result.gps = parseGps(inner);
            } else if (tag.equalsIgnoreCase("NMEA Data")) {
                result.nmea = parseNmea(inner);
            } else if (tag.equalsIgnoreCase("Analog Array Sensors")) {
                result.analogSensors = parseAnalogSensors(inner);
            } else if (tag.equalsIgnoreCase("Pi Temperature")) {
                result.piTemperature = parsePiTemperature(inner);
            } else {
                result.unknownSections.add(new RawSection(tag, inner));
            }
        } catch (Exception e) {
            // Be robust — if parsing fails just add to unknown
            result.unknownSections.add(new RawSection(tag, inner));
        }
    }

    // ── Section parsers ────────────────────────────────────────────────────────

    private static SoundAcquisitionData parseSoundAcquisition(String inner) throws Exception {
        // inner looks like: "Sound Acquisition:<RawDataSummary>...</RawDataSummary>"
        String xmlPart = extractXmlPart(inner, "RawDataSummary");
        if (xmlPart == null) return null;

        Document doc = parseXml("<root>" + xmlPart + "</root>");
        SoundAcquisitionData data = new SoundAcquisitionData();
        NodeList channels = doc.getElementsByTagName("channel");
        for (int i = 0; i < channels.getLength(); i++) {
            Element ch = (Element) channels.item(i);
            int idx    = intAttr(ch, "index", i);
            double mean  = doubleText(ch, "mean", 0);
            double peak  = doubleText(ch, "peakdB", -120);
            double rms   = doubleText(ch, "rmsdB", -120);
            data.channels.add(new ChannelLevel(idx, mean, peak, rms));
        }
        return data;
    }

    private static SoundRecorderData parseSoundRecorder(String inner) throws Exception {
        String xmlPart = extractXmlPart(inner, "RecorderSummary");
        if (xmlPart == null) return null;

        Document doc = parseXml("<root>" + xmlPart + "</root>");
        SoundRecorderData data = new SoundRecorderData();
        data.button     = textContent(doc, "button", "");
        data.state      = textContent(doc, "state", "");
        data.freeSpaceMb = doubleContent(doc, "freeSpaceMB", 0);
        data.fileSizeMb  = doubleContent(doc, "fileSizeMB", 0);

        // Channel amplitudes inside <channelAmplitudesdB>
        NodeList channels = doc.getElementsByTagName("channelAmplitudesdB");
        if (channels.getLength() > 0) {
            Element parent = (Element) channels.item(0);
            NodeList chList = parent.getElementsByTagName("channel");
            for (int i = 0; i < chList.getLength(); i++) {
                Element ch  = (Element) chList.item(i);
                int    idx  = intAttr(ch, "index", i);
                double ampl = Double.parseDouble(ch.getTextContent().trim());
                data.channelAmplitudes.add(new ChannelLevel(idx, 0, ampl, ampl));
            }
        }
        return data;
    }

    private static GpsData parseGps(String inner) throws Exception {
        String xmlPart = extractXmlPart(inner, "GPSSummary");
        if (xmlPart == null) return null;

        Document doc = parseXml("<root>" + xmlPart + "</root>");
        GpsData data    = new GpsData();
        data.status     = textContent(doc, "status", "");
        data.timestamp  = textContent(doc, "timestamp", "");
        data.latitude   = doubleContent(doc, "latitude", 0);
        data.longitude  = doubleContent(doc, "longitude", 0);
        data.headingDeg = doubleContent(doc, "headingDeg", 0);
        return data;
    }

    private static NmeaData parseNmea(String inner) {
        NmeaData data = new NmeaData();
        // inner looks like "NMEA Data Collection:$GPGSV,..."
        int colon = inner.indexOf(':');
        data.rawSentence = (colon >= 0 && colon + 1 < inner.length())
                ? inner.substring(colon + 1).trim()
                : inner.trim();
        return data;
    }

    private static AnalogSensorsData parseAnalogSensors(String inner) throws Exception {
        String xmlPart = extractXmlPart(inner, "AnalogSensorsSummary");
        if (xmlPart == null) return null;

        Document doc = parseXml("<root>" + xmlPart + "</root>");
        AnalogSensorsData data = new AnalogSensorsData();
        data.timeMillis = longContent(doc, "timeMillis", 0);

        // Find the AnalogSensorsSummary element and iterate its direct children
        NodeList summaryNodes = doc.getElementsByTagName("AnalogSensorsSummary");
        if (summaryNodes.getLength() == 0) return data;
        Element summary = (Element) summaryNodes.item(0);

        NodeList children = summary.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element sensor = (Element) children.item(i);
            if (sensor.getTagName().equals("timeMillis")) continue;
            double calVal  = doubleText(sensor, "calVal", Double.NaN);
            double voltage = doubleText(sensor, "voltage", Double.NaN);
            data.readings.add(new SensorReading(sensor.getTagName(), calVal, voltage));
        }
        return data;
    }

    private static PiTemperatureData parsePiTemperature(String inner) {
        PiTemperatureData data = new PiTemperatureData();
        // inner looks like "Pi Temperature:temp=49.9'C"
        try {
            int eqIdx = inner.indexOf('=');
            if (eqIdx >= 0) {
                String rest = inner.substring(eqIdx + 1).replaceAll("[^0-9.\\-].*", "").trim();
                data.tempCelsius = Double.parseDouble(rest);
            }
        } catch (Exception ignored) {}
        return data;
    }

    private static SystemDiagnosticsData parseSystemDiagnosticsXml(String xmlContent) throws Exception {
        // xmlContent is just <SystemDiagnostics>...</SystemDiagnostics>
        Document doc = parseXml("<root>" + xmlContent + "</root>");
        SystemDiagnosticsData data = new SystemDiagnosticsData();
        
        data.pamguardMemoryUsedMB = longContent(doc, "pamguardMemoryUsedMB", 0);
        data.pamguardMemoryTotalMB = longContent(doc, "pamguardMemoryTotalMB", 0);
        data.pamguardMemoryMaxMB = longContent(doc, "pamguardMemoryMaxMB", 0);
        data.systemMemoryUsedMB = longContent(doc, "systemMemoryUsedMB", 0);
        data.systemMemoryTotalMB = longContent(doc, "systemMemoryTotalMB", 0);
        
        // Parse CPU cores
        NodeList cpuCoresNodes = doc.getElementsByTagName("cpuCores");
        if (cpuCoresNodes.getLength() > 0) {
            Element cpuCoresEl = (Element) cpuCoresNodes.item(0);
            NodeList cores = cpuCoresEl.getElementsByTagName("core");
            for (int i = 0; i < cores.getLength(); i++) {
                Element core = (Element) cores.item(i);
                int index = intAttr(core, "index", i);
                double usage = Double.parseDouble(core.getTextContent().trim());
                data.cpuCores.add(new CpuCore(index, usage));
            }
        }
        
        return data;
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    /**
     * Extract the XML fragment starting with {@code <tagName>} and ending with
     * {@code </tagName>} from a mixed text+XML string.
     */
    private static String extractXmlPart(String text, String tagName) {
        String open  = "<" + tagName + ">";
        String openS = "<" + tagName + " "; // with attributes
        String close = "</" + tagName + ">";
        int start = text.indexOf(open);
        if (start < 0) start = text.indexOf(openS);
        if (start < 0) return null;
        int end = text.lastIndexOf(close);
        if (end < 0) return null;
        return text.substring(start, end + close.length());
    }

    /** Strip the outermost XML wrapper tag from a string. */
    private static String stripOuterTag(String xml, String tag) {
        // Build open/close patterns – tag names may contain spaces
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        int end   = xml.lastIndexOf(close);
        if (start < 0 || end < 0 || end <= start) return xml;
        return xml.substring(start + open.length(), end);
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        DocumentBuilder b = f.newDocumentBuilder();
        // Suppress XML parser stderr
        b.setErrorHandler(null);
        return b.parse(new InputSource(new StringReader(xml)));
    }

    private static String textContent(Document doc, String tag, String def) {
        NodeList nl = doc.getElementsByTagName(tag);
        if (nl.getLength() == 0) return def;
        return nl.item(0).getTextContent().trim();
    }

    private static double doubleContent(Document doc, String tag, double def) {
        try { return Double.parseDouble(textContent(doc, tag, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    private static long longContent(Document doc, String tag, long def) {
        try { return Long.parseLong(textContent(doc, tag, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    private static double doubleText(Element el, String tag, double def) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return def;
        try { return Double.parseDouble(nl.item(0).getTextContent().trim()); }
        catch (Exception e) { return def; }
    }

    private static int intAttr(Element el, String attr, int def) {
        String val = el.getAttribute(attr);
        if (val == null || val.isBlank()) return def;
        try { return Integer.parseInt(val.trim()); }
        catch (Exception e) { return def; }
    }
}
