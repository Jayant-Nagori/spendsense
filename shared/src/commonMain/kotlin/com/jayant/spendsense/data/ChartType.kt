package com.jayant.spendsense.data

/** Style of the dashboard spending chart. */
enum class ChartType(val label: String) {
    DONUT("Donut"), BAR("Bar"), LINE("Line");

    companion object {
        fun fromName(name: String?): ChartType = entries.firstOrNull { it.name == name } ?: DONUT
    }
}
