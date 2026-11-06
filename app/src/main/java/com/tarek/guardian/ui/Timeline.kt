package com.tarek.guardian.ui

import android.location.Location
import com.tarek.guardian.data.GeoZone
import com.tarek.guardian.data.LocPoint

/**
 * Turns a day's raw track into "stops": consecutive points within
 * [STOP_RADIUS_M] of each other are one place, shown with arrival and leaving
 * times. Matched against the parent's safe zones so "Home" and "School" read as
 * names rather than coordinates.
 */
object Timeline {

    data class Stop(
        val lat: Double,
        val lng: Double,
        val from: Long,
        val to: Long,
        val zone: String?
    )

    private const val STOP_RADIUS_M = 250f

    fun stops(points: List<LocPoint>, zones: List<GeoZone>): List<Stop> {
        val out = ArrayList<Stop>()
        var group = ArrayList<LocPoint>()
        fun flush() {
            if (group.isEmpty()) return
            val lat = group.map { it.lat }.average()
            val lng = group.map { it.lng }.average()
            out.add(Stop(lat, lng, group.first().ts, group.last().ts, zoneAt(lat, lng, zones)))
            group = ArrayList()
        }
        for (p in points.sortedBy { it.ts }) {
            val first = group.firstOrNull()
            if (first != null && distance(first.lat, first.lng, p.lat, p.lng) > STOP_RADIUS_M) flush()
            group.add(p)
        }
        flush()
        return out
    }

    private fun zoneAt(lat: Double, lng: Double, zones: List<GeoZone>): String? =
        zones.firstOrNull { distance(lat, lng, it.lat, it.lng) <= it.radiusM }?.name

    private fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, r)
        return r[0]
    }

    /** Google Maps directions through the day's stops (Maps allows ~10 points). */
    fun routeUrl(stops: List<Stop>): String {
        val pts = if (stops.size <= 10) stops
                  else stops.filterIndexed { i, _ -> i % ((stops.size + 9) / 10) == 0 } + stops.last()
        return "https://www.google.com/maps/dir/" +
            pts.joinToString("/") { "${it.lat},${it.lng}" }
    }
}
