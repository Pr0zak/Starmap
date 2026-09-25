package com.starmap.app

import com.starmap.app.aircraft.Metar
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetarTest {
    @Test
    fun parsesNumericAndVariableWind() {
        val s = Metar.parse(
            JSONArray(
                """[{"icaoId":"KBDU","name":"Boulder Muni","lat":40.04,"lon":-105.23,"wdir":270,"wspd":25,"wgst":32},
                   {"icaoId":"KBJC","name":"Rocky Mtn","lat":39.9,"lon":-105.1,"wdir":"VRB","wspd":3}]""",
            ),
        )
        assertEquals(2, s.size)
        assertEquals(270, s[0].dirDeg)
        assertEquals(32, s[0].gustKt)
        assertNull(s[1].dirDeg)
        assertNull(s[1].gustKt)
    }
}
