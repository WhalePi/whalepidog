package whalepidog.test;

import whalepidog.ui.SummaryParser;
import whalepidog.ui.SummaryParser.*;

/**
 * Quick smoke-test for SummaryParser against the real PAMGuard summary format.
 */
public class SummaryParserTest {

    static final String EXAMPLE =
        "  <Data Acquisition>Sound Acquisition:<RawDataSummary><channel index=\"0\"><mean>-0.0</mean><peakdB>-83.2</peakdB><rmsdB>-94.1</rmsdB></channel><channel index=\"1\"><mean>-0.0</mean><peakdB>-69.4</peakdB><rmsdB>-89.7</rmsdB></channel></RawDataSummary><\\Data Acquisition>\n" +
        "  <NMEA Data>NMEA Data Collection:$GPGSV,3,3,11,06,10,021,,04,03,343,,03,03,317,*4B<\\NMEA Data>\n" +
        "  <Sound Recorder>Sound recorder:<RecorderSummary><button>start</button><state>recording</state><freeSpaceMB>147108.4</freeSpaceMB><channelAmplitudesdB><channel index=\"0\">-81.49</channel><channel index=\"1\">-77.97</channel></channelAmplitudesdB></RecorderSummary><\\Sound Recorder>\n" +
        "  <GPS Acquisition>GPS Processing:<GPSSummary><status>ok</status><timestamp>2026-02-25 10:10:28.779</timestamp><latitude>56.449218</latitude><longitude>-2.883050</longitude><headingDeg>73.03</headingDeg></GPSSummary><\\GPS Acquisition>\n" +
        "  <Analog Array Sensors>Analog Array Sensors:<AnalogSensorsSummary><timeMillis>1772014228459</timeMillis><Depth><calVal>1.9395</calVal><voltage>-0.0605</voltage></Depth></AnalogSensorsSummary><\\Analog Array Sensors>\n" +
        "  <Pi Temperature>Pi Temperature:temp=49.9'C<\\Pi Temperature>\n";

    public static void main(String[] args) {
        System.out.println("=== SummaryParser Test ===\n");
        ParsedSummary ps = SummaryParser.parse(EXAMPLE);

        // Sound Acquisition
        if (ps.soundAcquisition != null) {
            System.out.println("Sound Acquisition: " + ps.soundAcquisition.channels.size() + " channel(s)");
            for (ChannelLevel ch : ps.soundAcquisition.channels) {
                System.out.printf("  Ch%d  peak=%.1f dB  rms=%.1f dB%n", ch.index, ch.peakDb, ch.rmsDb);
            }
        } else {
            System.out.println("Sound Acquisition: NULL (parse failed)");
        }

        // Sound Recorder
        if (ps.soundRecorder != null) {
            System.out.printf("Sound Recorder: state=%s  free=%.1f MB%n",
                    ps.soundRecorder.state, ps.soundRecorder.freeSpaceMb);
            for (ChannelLevel ch : ps.soundRecorder.channelAmplitudes) {
                System.out.printf("  Ch%d  amp=%.2f dB%n", ch.index, ch.peakDb);
            }
        } else {
            System.out.println("Sound Recorder: NULL");
        }

        // GPS
        if (ps.gps != null) {
            System.out.printf("GPS: status=%s  lat=%.6f  lon=%.6f  hdg=%.1f°%n",
                    ps.gps.status, ps.gps.latitude, ps.gps.longitude, ps.gps.headingDeg);
        } else {
            System.out.println("GPS: NULL");
        }

        // NMEA
        if (ps.nmea != null) {
            System.out.println("NMEA: " + ps.nmea.rawSentence);
        } else {
            System.out.println("NMEA: NULL");
        }

        // Analog sensors
        if (ps.analogSensors != null) {
            System.out.println("Analog Sensors: timeMillis=" + ps.analogSensors.timeMillis);
            for (SensorReading r : ps.analogSensors.readings) {
                System.out.printf("  %-20s  calVal=%.4f  voltage=%.4f%n", r.name, r.calVal, r.voltage);
            }
        } else {
            System.out.println("Analog Sensors: NULL");
        }

        // Pi temperature
        if (ps.piTemperature != null) {
            System.out.printf("Pi Temperature: %.1f C%n", ps.piTemperature.tempCelsius);
        } else {
            System.out.println("Pi Temperature: NULL");
        }

        // Unknown
        System.out.println("Unknown sections: " + ps.unknownSections.size());
        for (RawSection sec : ps.unknownSections) {
            System.out.println("  [" + sec.tag + "] " + sec.content);
        }

        System.out.println("\n=== PASS ===");
    }
}
