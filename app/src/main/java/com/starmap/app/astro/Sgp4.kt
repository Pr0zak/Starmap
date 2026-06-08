package com.starmap.app.astro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SGP4 orbital propagator (near-Earth branch, WGS-72) for satellites given as
 * NORAD two-line elements. This is the classic Spacetrack Report #3 formulation,
 * which is accurate to ~1 km for low-Earth-orbit objects like the ISS and
 * Starlink — far better than needed to point a phone at a passing satellite.
 *
 * Deep-space objects (orbital period >= 225 min) are not supported and report
 * [deepSpace] = true; the satellites this app shows are all low-Earth-orbit.
 */
class Sgp4(val tle: Tle) {

    val deepSpace: Boolean

    // Recovered/derived constants computed once at construction.
    private val xnodp: Double
    private val aodp: Double
    private val cosio: Double
    private val sinio: Double
    private val x3thm1: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val c1: Double
    private val c4: Double
    private val c5: Double
    private val xmdot: Double
    private val omgdot: Double
    private val xnodot: Double
    private val xnodcf: Double
    private val t2cof: Double
    private val xlcof: Double
    private val aycof: Double
    private val omgcof: Double
    private val xmcof: Double
    private val delmo: Double
    private val sinmo: Double
    private val eta: Double
    private val bstar: Double
    private val eo: Double
    private val xincl: Double
    private val xnodeo: Double
    private val omegao: Double
    private val xmo: Double
    private val isimp: Boolean
    private var d2 = 0.0
    private var d3 = 0.0
    private var d4 = 0.0
    private var t3cof = 0.0
    private var t4cof = 0.0
    private var t5cof = 0.0

    init {
        eo = tle.eccentricity
        xincl = tle.inclinationRad
        xnodeo = tle.raanRad
        omegao = tle.argPerigeeRad
        xmo = tle.meanAnomalyRad
        bstar = tle.bstar
        val xno = tle.meanMotionRadPerMin

        val period = 2.0 * PI / xno // minutes
        deepSpace = period >= 225.0

        val a1 = (XKE / xno).pow(TOTHRD)
        cosio = cos(xincl)
        val theta2 = cosio * cosio
        x3thm1 = 3.0 * theta2 - 1.0
        val eosq = eo * eo
        val betao2 = 1.0 - eosq
        val betao = sqrt(betao2)
        val del1 = 1.5 * CK2 * x3thm1 / (a1 * a1 * betao * betao2)
        val ao = a1 * (1.0 - del1 * (0.5 * TOTHRD + del1 * (1.0 + 134.0 / 81.0 * del1)))
        val delo = 1.5 * CK2 * x3thm1 / (ao * ao * betao * betao2)
        xnodp = xno / (1.0 + delo)
        aodp = ao / (1.0 - delo)

        isimp = (aodp * (1.0 - eo) / AE) < (220.0 / XKMPER + AE)

        var s4 = S
        var qoms24 = QOMS2T
        val perige = (aodp * (1.0 - eo) - AE) * XKMPER
        if (perige < 156.0) {
            s4 = if (perige <= 98.0) 20.0 else perige - 78.0
            qoms24 = ((120.0 - s4) * AE / XKMPER).pow(4.0)
            s4 = s4 / XKMPER + AE
        }

        val pinvsq = 1.0 / (aodp * aodp * betao2 * betao2)
        val tsi = 1.0 / (aodp - s4)
        eta = aodp * eo * tsi
        val etasq = eta * eta
        val eeta = eo * eta
        val psisq = abs(1.0 - etasq)
        val coef = qoms24 * tsi.pow(4.0)
        val coef1 = coef / psisq.pow(3.5)
        val c2 = coef1 * xnodp * (aodp * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
            0.75 * CK2 * tsi / psisq * x3thm1 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        c1 = bstar * c2
        sinio = sin(xincl)
        val a3ovk2 = -XJ3 / CK2 * AE * AE * AE
        val c3 = coef * tsi * a3ovk2 * xnodp * AE * sinio / eo
        x1mth2 = 1.0 - theta2
        c4 = 2.0 * xnodp * coef1 * aodp * betao2 * (
            eta * (2.0 + 0.5 * etasq) + eo * (0.5 + 2.0 * etasq) -
                2.0 * CK2 * tsi / (aodp * psisq) * (
                    -3.0 * x3thm1 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                        0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * omegao)
                    )
            )
        c5 = 2.0 * coef1 * aodp * betao2 * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val theta4 = theta2 * theta2
        val temp1 = 3.0 * CK2 * pinvsq * xnodp
        val temp2 = temp1 * CK2 * pinvsq
        val temp3 = 1.25 * CK4 * pinvsq * pinvsq * xnodp
        xmdot = xnodp + 0.5 * temp1 * betao * x3thm1 +
            0.0625 * temp2 * betao * (13.0 - 78.0 * theta2 + 137.0 * theta4)
        val x1m5th = 1.0 - 5.0 * theta2
        omgdot = -0.5 * temp1 * x1m5th + 0.0625 * temp2 * (7.0 - 114.0 * theta2 + 395.0 * theta4) +
            temp3 * (3.0 - 36.0 * theta2 + 49.0 * theta4)
        val xhdot1 = -temp1 * cosio
        xnodot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * theta2) +
            2.0 * temp3 * (3.0 - 7.0 * theta2)) * cosio
        omgcof = bstar * c3 * cos(omegao)
        xmcof = if (eo > 1e-4) -TOTHRD * coef * bstar * AE / eeta else 0.0
        xnodcf = 3.5 * betao2 * xhdot1 * c1
        t2cof = 1.5 * c1
        xlcof = 0.125 * a3ovk2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        aycof = 0.25 * a3ovk2 * sinio
        delmo = (1.0 + eta * cos(xmo)).pow(3.0)
        sinmo = sin(xmo)
        x7thm1 = 7.0 * theta2 - 1.0

        if (!isimp) {
            val c1sq = c1 * c1
            d2 = 4.0 * aodp * tsi * c1sq
            val temp = d2 * tsi * c1 / 3.0
            d3 = (17.0 * aodp + s4) * temp
            d4 = 0.5 * temp * aodp * tsi * (221.0 * aodp + 31.0 * s4) * c1
            t3cof = d2 + 2.0 * c1sq
            t4cof = 0.25 * (3.0 * d3 + c1 * (12.0 * d2 + 10.0 * c1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * c1 * d3 + 6.0 * d2 * d2 + 15.0 * c1sq * (2.0 * d2 + c1sq))
        }
    }

    /**
     * Position in the TEME frame, kilometres, [tsinceMin] minutes after epoch.
     * Returns null for deep-space objects.
     */
    fun positionTeme(tsinceMin: Double): DoubleArray? {
        if (deepSpace) return null

        val xmdf = xmo + xmdot * tsinceMin
        val omgadf = omegao + omgdot * tsinceMin
        val xnoddf = xnodeo + xnodot * tsinceMin
        var omega = omgadf
        var xmp = xmdf
        val tsq = tsinceMin * tsinceMin
        val xnode = xnoddf + xnodcf * tsq
        var tempa = 1.0 - c1 * tsinceMin
        var tempe = bstar * c4 * tsinceMin
        var templ = t2cof * tsq

        if (!isimp) {
            val delomg = omgcof * tsinceMin
            val delm = xmcof * ((1.0 + eta * cos(xmdf)).pow(3.0) - delmo)
            val temp = delomg + delm
            xmp = xmdf + temp
            omega = omgadf - temp
            val t3 = tsq * tsinceMin
            val t4 = t3 * tsinceMin
            val t5 = t4 * tsinceMin
            tempa = tempa - d2 * tsq - d3 * t3 - d4 * t4
            tempe += bstar * c5 * (sin(xmp) - sinmo)
            templ += t3cof * t3 + t4 * (t4cof + tsinceMin * t5cof)
        }

        val a = aodp * tempa * tempa
        val e = (eo - tempe).coerceIn(1e-6, 0.999999)
        val xl = xmp + omega + xnode + xnodp * templ
        val beta = sqrt(1.0 - e * e)
        val xn = XKE / a.pow(1.5)

        // Long-period periodics.
        val axn = e * cos(omega)
        var temp = 1.0 / (a * beta * beta)
        val xll = temp * xlcof * axn
        val aynl = temp * aycof
        val xlt = xl + xll
        val ayn = e * sin(omega) + aynl

        // Solve Kepler's equation for (E + omega).
        val capu = mod2pi(xlt - xnode)
        var epw = capu
        var sinepw = 0.0
        var cosepw = 0.0
        for (iter in 0 until 12) {
            sinepw = sin(epw)
            cosepw = cos(epw)
            val ecose = axn * cosepw + ayn * sinepw
            val esine = axn * sinepw - ayn * cosepw
            val f = capu - epw + esine
            val df = 1.0 - ecose
            var delta = f / df
            if (abs(delta) >= 0.95) delta = sign(delta) * 0.95
            epw += delta
            if (abs(delta) < 1e-12) break
        }

        val ecose = axn * cosepw + ayn * sinepw
        val esine = axn * sinepw - ayn * cosepw
        val elsq = axn * axn + ayn * ayn
        temp = 1.0 - elsq
        val pl = a * temp
        val r = a * (1.0 - ecose)
        val temp1 = 1.0 / r
        val betal = sqrt(temp)
        val temp3 = 1.0 / (1.0 + betal)
        val cosu = a * temp1 * (cosepw - axn + ayn * esine * temp3)
        val sinu = a * temp1 * (sinepw - ayn - axn * esine * temp3)
        val u = atan2(sinu, cosu)
        val sin2u = 2.0 * sinu * cosu
        val cos2u = 2.0 * cosu * cosu - 1.0
        val tempA = 1.0 / pl
        val tempB = CK2 * tempA
        val tempC = tempB * tempA

        val rk = r * (1.0 - 1.5 * tempC * betal * x3thm1) + 0.5 * tempB * x1mth2 * cos2u
        val uk = u - 0.25 * tempC * x7thm1 * sin2u
        val xnodek = xnode + 1.5 * tempC * cosio * sin2u
        val xinck = xincl + 1.5 * tempC * cosio * sinio * cos2u

        val sinuk = sin(uk); val cosuk = cos(uk)
        val sinik = sin(xinck); val cosik = cos(xinck)
        val sinnok = sin(xnodek); val cosnok = cos(xnodek)
        val xmx = -sinnok * cosik
        val xmy = cosnok * cosik
        val ux = xmx * sinuk + cosnok * cosuk
        val uy = xmy * sinuk + sinnok * cosuk
        val uz = sinik * sinuk

        return doubleArrayOf(rk * ux * XKMPER, rk * uy * XKMPER, rk * uz * XKMPER)
    }

    private fun mod2pi(x: Double): Double {
        var r = x % (2.0 * PI)
        if (r < 0) r += 2.0 * PI
        return r
    }

    companion object {
        private const val CK2 = 5.413080e-4
        private const val CK4 = 0.62098875e-6
        private const val XKMPER = 6378.135
        private const val AE = 1.0
        private const val XJ3 = -0.253881e-5
        private const val XKE = 0.743669161e-1
        private const val QOMS2T = 1.88027916e-9
        private const val S = 1.01222928
        private const val TOTHRD = 2.0 / 3.0
    }
}

/** A parsed NORAD two-line element set. */
class Tle(val name: String, line1: String, line2: String) {
    val eccentricity: Double
    val inclinationRad: Double
    val raanRad: Double
    val argPerigeeRad: Double
    val meanAnomalyRad: Double
    val meanMotionRadPerMin: Double
    val bstar: Double
    val jdEpoch: Double

    init {
        val d2r = Math.PI / 180.0
        inclinationRad = line2.substring(8, 16).trim().toDouble() * d2r
        raanRad = line2.substring(17, 25).trim().toDouble() * d2r
        eccentricity = ("0." + line2.substring(26, 33).trim()).toDouble()
        argPerigeeRad = line2.substring(34, 42).trim().toDouble() * d2r
        meanAnomalyRad = line2.substring(43, 51).trim().toDouble() * d2r
        val revPerDay = line2.substring(52, 63).trim().toDouble()
        meanMotionRadPerMin = revPerDay * 2.0 * Math.PI / 1440.0
        bstar = parseExp(line1.substring(53, 61))

        val epochYear = line1.substring(18, 20).trim().toInt()
        val epochDay = line1.substring(20, 32).trim().toDouble()
        val year = if (epochYear < 57) 2000 + epochYear else 1900 + epochYear
        jdEpoch = jdFromCalendar(year, 1, 1.0) + (epochDay - 1.0)
    }

    private fun parseExp(field: String): Double {
        val t = field.trim()
        if (t.isEmpty()) return 0.0
        val signMantissa = if (t.startsWith("-")) -1.0 else 1.0
        val body = t.trimStart('+', '-')
        if (body.isEmpty()) return 0.0
        // Exponent sign appears after the mantissa digits, e.g. "66816-4".
        val expIdx = body.indexOfFirst { it == '+' || it == '-' }
        return if (expIdx <= 0) {
            signMantissa * ("0." + body).toDouble()
        } else {
            val mantissa = ("0." + body.substring(0, expIdx)).toDouble()
            val exp = body.substring(expIdx).toInt()
            signMantissa * mantissa * 10.0.pow(exp)
        }
    }

    private fun jdFromCalendar(y: Int, m: Int, d: Double): Double {
        var yy = y; var mm = m
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = floor(yy / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5
    }
}

/** Parses a multi-satellite TLE text block (Celestrak "TLE" format: name, L1, L2). */
object TleParser {
    fun parse(text: String): List<Tle> {
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        val out = ArrayList<Tle>()
        var i = 0
        while (i + 2 < lines.size + 1 && i + 1 < lines.size) {
            val a = lines[i]
            if (a.startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ")) {
                // No name line (bare TLE) — use the catalog number as the name.
                runCatching { out.add(Tle(a.substring(2, 7).trim(), a, lines[i + 1])) }
                i += 2
            } else if (i + 2 < lines.size && lines[i + 1].startsWith("1 ") && lines[i + 2].startsWith("2 ")) {
                runCatching { out.add(Tle(a.trim(), lines[i + 1], lines[i + 2])) }
                i += 3
            } else {
                i += 1
            }
        }
        return out
    }
}
