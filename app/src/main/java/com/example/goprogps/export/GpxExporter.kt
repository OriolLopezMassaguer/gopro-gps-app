package com.example.goprogps.export

import com.example.goprogps.model.GpsTrack
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

object GpxExporter {

    fun export(track: GpsTrack, out: OutputStream, name: String = "GoPro Track") {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"GoProGPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>${name.escapeXml()}</name><time>${sdf.format(Date())}</time></metadata>\n")
        sb.append("  <trk><name>${name.escapeXml()}</name><trkseg>\n")

        for (p in track.points) {
            val time = sdf.format(Date(p.timestampMs))
            sb.append("    <trkpt lat=\"%.8f\" lon=\"%.8f\">".format(p.latitude, p.longitude))
            sb.append("<ele>%.2f</ele>".format(p.altitude))
            sb.append("<time>$time</time>")
            sb.append("<extensions><speed>%.3f</speed></extensions>".format(p.speed2d))
            sb.append("</trkpt>\n")
        }

        sb.append("  </trkseg></trk>\n</gpx>\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
