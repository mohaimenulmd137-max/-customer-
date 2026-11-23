package com.example.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CurrencyUtils {

    private val bengaliDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    fun toBengaliDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(bengaliDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val idx = bengaliDigits.indexOf(ch)
            if (idx >= 0) {
                sb.append(('0'.code + idx).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Parses user-entered currency text (supports Bengali or English digits) into exact Long paisa (1 BDT = 100 paisa).
     * Never uses Double/Float arithmetic.
     */
    fun parseBdtToPaisa(input: String): Long? {
        val normalized = normalizeDigits(input.trim()).replace(",", "")
        if (normalized.isEmpty()) return null
        return try {
            val bd = BigDecimal(normalized)
            if (bd < BigDecimal.ZERO) return null
            bd.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses quantity string into thousandths (1.0 -> 1000L, 0.250 -> 250L).
     */
    fun parseQuantityMillis(input: String): Long? {
        val normalized = normalizeDigits(input.trim()).replace(",", "")
        if (normalized.isEmpty()) return null
        return try {
            val bd = BigDecimal(normalized)
            if (bd <= BigDecimal.ZERO) return null
            bd.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Exact integer/BigDecimal line total: (quantityMillis * unitPricePaisa) / 1000 rounded HALF_UP.
     */
    fun calculateLineTotalPaisa(quantityMillis: Long, unitPricePaisa: Long): Long {
        if (quantityMillis <= 0L || unitPricePaisa < 0L) return 0L
        return BigDecimal.valueOf(quantityMillis)
            .multiply(BigDecimal.valueOf(unitPricePaisa))
            .divide(BigDecimal.valueOf(1000L), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun formatPaisaToPlainInput(paisa: Long): String {
        val whole = paisa / 100L
        val frac = kotlin.math.abs(paisa % 100L)
        return if (frac == 0L) {
            whole.toString()
        } else {
            "$whole.${frac.toString().padStart(2, '0')}"
        }
    }

    fun formatQuantityPlain(quantityMillis: Long): String {
        val bd = BigDecimal.valueOf(quantityMillis).divide(BigDecimal.valueOf(1000L))
        return bd.stripTrailingZeros().toPlainString()
    }

    fun formatQuantityBn(quantityMillis: Long): String {
        return toBengaliDigits(formatQuantityPlain(quantityMillis))
    }

    fun formatBdt(paisa: Long, symbol: String = "৳", useBengaliDigits: Boolean = true): String {
        val isNegative = paisa < 0L
        val absPaisa = kotlin.math.abs(paisa)
        val whole = absPaisa / 100L
        val frac = absPaisa % 100L

        val formattedWhole = formatSouthAsianGrouping(whole)
        val raw = if (frac == 0L) {
            "$symbol $formattedWhole"
        } else {
            "$symbol $formattedWhole.${frac.toString().padStart(2, '0')}"
        }
        val signed = if (isNegative) "-$raw" else raw
        return if (useBengaliDigits) toBengaliDigits(signed) else signed
    }

    private fun formatSouthAsianGrouping(number: Long): String {
        val s = number.toString()
        if (s.length <= 3) return s
        val lastThree = s.substring(s.length - 3)
        val rest = s.substring(0, s.length - 3)
        val sb = StringBuilder()
        var count = 0
        for (i in rest.length - 1 downTo 0) {
            sb.append(rest[i])
            count++
            if (count == 2 && i != 0) {
                sb.append(',')
                count = 0
            }
        }
        return sb.reverse().toString() + "," + lastThree
    }

    fun startOfDayMillis(timeMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun endOfDayMillis(timeMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun startOfMonthMillis(year: Int, monthZeroBased: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, monthZeroBased, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun endOfMonthMillis(year: Int, monthZeroBased: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, monthZeroBased, 1, 23, 59, 59)
        cal.set(Calendar.MILLISECOND, 999)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        return cal.timeInMillis
    }

    fun formatDateBn(timeMillis: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        return toBengaliDigits(sdf.format(Date(timeMillis)))
    }

    fun formatTimeBn(timeMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val period = when {
            hour24 < 6 -> "রাত"
            hour24 < 12 -> "সকাল"
            hour24 < 16 -> "দুপুর"
            hour24 < 19 -> "বিকাল"
            else -> "রাত"
        }
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val raw = "${hour12.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        return "$period ${toBengaliDigits(raw)}"
    }

    fun formatDateTimeBn(timeMillis: Long): String {
        return "${formatDateBn(timeMillis)}, ${formatTimeBn(timeMillis)}"
    }

    fun weekdayBn(timeMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "শনিবার"
            Calendar.SUNDAY -> "রবিবার"
            Calendar.MONDAY -> "সোমবার"
            Calendar.TUESDAY -> "মঙ্গলবার"
            Calendar.WEDNESDAY -> "বুধবার"
            Calendar.THURSDAY -> "বৃহস্পতিবার"
            Calendar.FRIDAY -> "শুক্রবার"
            else -> ""
        }
    }

    fun daysBetween(olderMillis: Long, newerMillis: Long = System.currentTimeMillis()): Int {
        val startOlder = startOfDayMillis(olderMillis)
        val startNewer = startOfDayMillis(newerMillis)
        val diff = startNewer - startOlder
        if (diff <= 0L) return 0
        return (diff / (24L * 60L * 60L * 1000L)).toInt()
    }
}
