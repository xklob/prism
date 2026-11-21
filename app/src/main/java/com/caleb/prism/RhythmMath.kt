package com.caleb.prism

/** Beat and bar indexes can be negative when extrapolating before the first lock. */
internal fun floorMod(index: Int, period: Int): Int = index.mod(period)
internal fun floorMod(index: Long, period: Long): Long = index.mod(period)
