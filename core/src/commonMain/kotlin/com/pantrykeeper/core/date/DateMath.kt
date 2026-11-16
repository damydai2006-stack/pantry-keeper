package com.pantrykeeper.core.date

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

enum class ShelfUnit {
    DAY,
    MONTH,
    YEAR,
}

object DateMath {
    const val MIN_YEAR = 2000
    const val MAX_YEAR = 2045
    const val MAX_SHELF_DAYS = 3650
    const val MAX_SHELF_MONTHS = 120
    const val MAX_SHELF_YEARS = 10

    fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    fun lastDayOfMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(year)) 29 else 28
        else -> throw IllegalArgumentException("month")
    }

    fun plusDays(date: LocalDate, days: Int): LocalDate = date.plus(days, DateTimeUnit.DAY)

    fun plusMonths(date: LocalDate, months: Int): LocalDate {
        val monthIndex = date.monthNumber - 1 + months
        val year = date.year + floorDiv(monthIndex, 12)
        val month = floorMod(monthIndex, 12) + 1
        val day = minOf(date.dayOfMonth, lastDayOfMonth(year, month))
        return LocalDate(year, month, day)
    }

    fun plusYears(date: LocalDate, years: Int): LocalDate = plusMonths(date, years * 12)

    fun addShelf(start: LocalDate, value: Int, unit: ShelfUnit): LocalDate = when (unit) {
        ShelfUnit.DAY -> plusDays(start, value)
        ShelfUnit.MONTH -> plusMonths(start, value)
        ShelfUnit.YEAR -> plusYears(start, value)
    }

    fun shelfAllowed(value: Int, unit: ShelfUnit): Boolean = when (unit) {
        ShelfUnit.DAY -> value in 1..MAX_SHELF_DAYS
        ShelfUnit.MONTH -> value in 1..MAX_SHELF_MONTHS
        ShelfUnit.YEAR -> value in 1..MAX_SHELF_YEARS
    }

    fun daysBetween(from: LocalDate, to: LocalDate): Int =
        (to.toEpochDays() - from.toEpochDays()).toInt()
}

private fun floorDiv(a: Int, b: Int): Int {
    val q = a / b
    val r = a % b
    return if (r != 0 && a < 0) q - 1 else q
}

private fun floorMod(a: Int, b: Int): Int = a - floorDiv(a, b) * b
