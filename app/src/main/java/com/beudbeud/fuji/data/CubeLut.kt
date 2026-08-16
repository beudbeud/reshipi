package com.beudbeud.fuji.data

/**
 * Builds a 3D LUT by observing what the camera did to an image.
 *
 * A RAF is developed twice — once neutral, once with the recipe — and every
 * pixel pair is one (input colour → output colour) sample. A single frame gives
 * millions of them, so no colour chart is needed.
 *
 * The catch is coverage: a scene only contains the colours it contains, and a
 * chart only the colours the camera can render. [coverage] reports how much of
 * the cube a real measurement backs, which is the honest quality figure.
 *
 * Clarity and grain are spatial effects and cannot be represented here at all.
 */
class CubeLut(val size: Int = 33) {
    init { require(size in 2..64) { "LUT size $size out of range" } }

    private val nodes = size * size * size

    /** Cells are the gaps between nodes, so one fewer per axis. */
    private val side = size - 1
    private val cellCount = side * side * side

    /**
     * One cell's contribution to the normal equations: the upper triangle of the
     * 8x8 corner-weight products, then eight corners times three channels of
     * right-hand side.
     */
    private val block = DoubleArray(cellCount * BLOCK)
    private val used = BooleanArray(cellCount)

    /** Total sample weight landing on each node, for [coverage] only. */
    private val weight = DoubleArray(nodes)

    private val corner = DoubleArray(8)
    private val at = IntArray(8)

    /** Red varies fastest, as the .cube format requires. */
    private fun index(r: Int, g: Int, b: Int) = (b * size + g) * size + r

    /**
     * Record that input colour [src] came out as [dst]. Channels are 0..255.
     *
     * What is stored is not the sample but its contribution to a least-squares
     * problem: find the node values whose *trilinear interpolation* best
     * reproduces every sample. That is the question that matters, because
     * trilinear interpolation is exactly what the software reading the .cube
     * will do — fitting anything else means fitting the wrong thing.
     *
     * Only the deviation from the identity LUT is fitted. Interpolating the
     * identity returns the input exactly, so the residual is what the recipe
     * added, and a cell no sample reached simply stays at zero deviation — no
     * separate pass has to go and fill it in.
     */
    fun accumulate(srcR: Int, srcG: Int, srcB: Int, dstR: Int, dstG: Int, dstB: Int) {
        val span = size - 1
        val fr = srcR.coerceIn(0, 255) * span / 255.0
        val fg = srcG.coerceIn(0, 255) * span / 255.0
        val fb = srcB.coerceIn(0, 255) * span / 255.0
        // Clamped one short of the top so the upper corner of each cell exists;
        // a sample sitting exactly on the last node then has t = 1 and lands
        // wholly on it, which is what we want.
        val r0 = fr.toInt().coerceIn(0, span - 1)
        val g0 = fg.toInt().coerceIn(0, span - 1)
        val b0 = fb.toInt().coerceIn(0, span - 1)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0

        var n = 0
        for (i in 0..1) {
            val wr = if (i == 0) 1.0 - tr else tr
            for (j in 0..1) {
                val wg = wr * (if (j == 0) 1.0 - tg else tg)
                for (k in 0..1) {
                    corner[n] = wg * (if (k == 0) 1.0 - tb else tb)
                    weight[index(r0 + i, g0 + j, b0 + k)] += corner[n]
                    n++
                }
            }
        }

        val dr = (dstR - srcR) / 255.0
        val dg = (dstG - srcG) / 255.0
        val db = (dstB - srcB) / 255.0
        val cell = (b0 * side + g0) * side + r0
        used[cell] = true
        var p = cell * BLOCK
        for (a in 0..7) {
            val wa = corner[a]
            for (b in a..7) block[p++] += wa * corner[b]
        }
        for (a in 0..7) {
            val wa = corner[a]
            block[p++] += wa * dr
            block[p++] += wa * dg
            block[p++] += wa * db
        }
    }

    /**
     * Total evidence below which a cell is noise rather than a measurement.
     *
     * Splatting spreads every sample over eight nodes, so a node can end up
     * holding a sliver of one sample from a colour that never really landed near
     * it. On a real export that was 59% of the nodes touched. The bar is a share
     * of what a typical node carries, so it means the same thing whether the
     * export gathered four million samples or four.
     */
    private fun trustFloor(): Double {
        val touched = weight.count { it > 0.0 }
        return if (touched == 0) 0.0 else weight.sum() / touched * THIN_NODE
    }

    /** Fraction of the cube backed by a real measurement rather than inferred, 0..1. */
    fun coverage(): Float {
        val floor = trustFloor()
        if (floor <= 0.0) return 0f
        return weight.count { it >= floor }.toFloat() / nodes
    }

    /** Writes the eight node indices of [cell] into [at]. */
    private fun cornersOf(cell: Int) {
        val r0 = cell % side
        val g0 = (cell / side) % side
        val b0 = cell / (side * side)
        var n = 0
        for (i in 0..1) for (j in 0..1) for (k in 0..1) {
            at[n++] = index(r0 + i, g0 + j, b0 + k)
        }
    }

    /** out = (AᵀA + smooth·L + pull·I) · x, over the three channels at once. */
    private fun apply(x: DoubleArray, out: DoubleArray, smooth: Double, pull: Double) {
        java.util.Arrays.fill(out, 0.0)
        for (cell in 0 until cellCount) {
            if (!used[cell]) continue
            cornersOf(cell)
            var p = cell * BLOCK
            for (a in 0..7) {
                val ia = at[a] * 3
                for (b in a..7) {
                    val m = block[p++]
                    if (m == 0.0) continue
                    val ib = at[b] * 3
                    if (a == b) {
                        for (c in 0..2) out[ia + c] += m * x[ia + c]
                    } else {
                        for (c in 0..2) {
                            out[ia + c] += m * x[ib + c]
                            out[ib + c] += m * x[ia + c]
                        }
                    }
                }
            }
        }
        // Smoothness between neighbouring nodes, plus a pull towards leaving the
        // colour alone. Together they decide what happens where nothing was
        // measured: neighbours drag a node into line, and far from any data the
        // pull wins and the deviation fades to nothing.
        for (i in 0 until nodes) {
            val r = i % size
            val g = (i / size) % size
            val b = i / (size * size)
            var degree = 0
            val base = i * 3
            for ((dr, dg, db) in NEIGHBOURS) {
                val nr = r + dr; val ng = g + dg; val nb = b + db
                if (nr !in 0 until size || ng !in 0 until size || nb !in 0 until size) continue
                degree++
                val j = index(nr, ng, nb) * 3
                for (c in 0..2) out[base + c] -= smooth * x[j + c]
            }
            for (c in 0..2) out[base + c] += (smooth * degree + pull) * x[base + c]
        }
    }

    /**
     * The node values whose trilinear interpolation best matches every sample.
     *
     * One solve replaces what used to be four passes — average per node, drop the
     * thinly-measured ones, dilate outwards, then relax the result — each of
     * which had its own failure: fronts meeting in a cliff, then flat plateaus
     * where the smoothing was made monotone. Here the same three demands are one
     * system: match the data, stay smooth, and fade to the identity where there
     * is no data. Conjugate gradient solves it, Jacobi-preconditioned.
     */
    fun build(): FloatArray {
        // Scale the regularisers to the data so they mean the same thing for a
        // four-million-sample chart and for a two-sample test.
        var diagonal = 0.0
        var counted = 0
        for (cell in 0 until cellCount) {
            if (!used[cell]) continue
            var p = cell * BLOCK
            for (a in 0..7) {
                diagonal += block[p]
                counted++
                p += 8 - a
            }
        }
        val scale = if (counted == 0) 1.0 else diagonal / counted
        val smooth = SMOOTH * scale
        val pull = PULL * scale

        val n = nodes * 3
        val rhs = DoubleArray(n)
        for (cell in 0 until cellCount) {
            if (!used[cell]) continue
            cornersOf(cell)
            var p = cell * BLOCK + 36
            for (a in 0..7) {
                val base = at[a] * 3
                for (c in 0..2) rhs[base + c] += block[p++]
            }
        }

        // Jacobi preconditioner: the diagonal of the whole system.
        val inverse = DoubleArray(nodes)
        run {
            val diag = DoubleArray(nodes)
            for (cell in 0 until cellCount) {
                if (!used[cell]) continue
                cornersOf(cell)
                var p = cell * BLOCK
                for (a in 0..7) {
                    diag[at[a]] += block[p]
                    p += 8 - a
                }
            }
            for (i in 0 until nodes) {
                val r = i % size
                val g = (i / size) % size
                val b = i / (size * size)
                var degree = 0
                for ((dr, dg, db) in NEIGHBOURS) {
                    if (r + dr !in 0 until size || g + dg !in 0 until size ||
                        b + db !in 0 until size
                    ) continue
                    degree++
                }
                val d = diag[i] + smooth * degree + pull
                inverse[i] = if (d > 0.0) 1.0 / d else 0.0
            }
        }

        val x = DoubleArray(n)
        val r = rhs.copyOf()
        val z = DoubleArray(n)
        for (i in 0 until nodes) for (c in 0..2) z[i * 3 + c] = inverse[i] * r[i * 3 + c]
        val p = z.copyOf()
        val ap = DoubleArray(n)
        var rz = dot(r, z)
        val target = rz * TOLERANCE
        var iterations = 0
        while (iterations < MAX_STEPS && rz > target && rz > 0.0) {
            apply(p, ap, smooth, pull)
            val denominator = dot(p, ap)
            if (denominator <= 0.0) break
            val alpha = rz / denominator
            for (i in 0 until n) {
                x[i] += alpha * p[i]
                r[i] -= alpha * ap[i]
            }
            for (i in 0 until nodes) for (c in 0..2) z[i * 3 + c] = inverse[i] * r[i * 3 + c]
            val next = dot(r, z)
            val beta = next / rz
            for (i in 0 until n) p[i] = z[i] + beta * p[i]
            rz = next
            iterations++
        }
        val held = project(x, rhs, smooth, pull)
        DebugLog.log(
            "lut solve: $iterations steps, residual ${"%.2e".format(rz)}, " +
                "$held of ${nodes * 3} held at a bound"
        )

        // Deviations become absolute values by adding back the identity they were
        // measured against.
        val out = FloatArray(n)
        for (i in 0 until nodes) {
            val rr = (i % size).toFloat() / (size - 1)
            val gg = ((i / size) % size).toFloat() / (size - 1)
            val bb = (i / (size * size)).toFloat() / (size - 1)
            // Already inside 0..1 by construction — the projection above keeps
            // each deviation within reach of its own identity value.
            out[i * 3] = (rr + x[i * 3]).toFloat()
            out[i * 3 + 1] = (gg + x[i * 3 + 1]).toFloat()
            out[i * 3 + 2] = (bb + x[i * 3 + 2]).toFloat()
        }
        val floor = trustFloor()
        val grey = (0 until size).count { weight[index(it, it, it)] >= floor }
        DebugLog.log("lut cells: ${weight.count { it >= floor }}/$nodes measured, grey axis $grey/$size")
        return out
    }

    /** Offset of M[a][b] inside a cell's packed upper triangle, for a <= b. */
    private fun triangle(a: Int, b: Int) = (8 * a - a * (a - 1) / 2) + (b - a)

    /**
     * Settles [x] against its bounds, which the unconstrained solve ignores.
     *
     * Clamping only at the end leaves a node pinned at the edge while all its
     * neighbours were fitted expecting the value it wanted, which is how both
     * published generators describe their remaining defect — outliers at extreme
     * colours, wrong boundary entries despite enough data. Gauss-Seidel visits
     * one node at a time and clamps as it goes, so every later node sees the
     * clamped value and settles around it.
     *
     * Returns how many node-channels the bounds actually held back; on a cube
     * that never leaves range this is zero and nothing was changed.
     */
    private fun project(x: DoubleArray, rhs: DoubleArray, smooth: Double, pull: Double): Int {
        var held = 0
        repeat(SWEEPS) { sweep ->
            held = 0
            for (i in 0 until nodes) {
                val r = i % size
                val g = (i / size) % size
                val b = i / (size * size)
                val base = i * 3
                val off = DoubleArray(3)
                var diagonal = pull

                // Every cell this node is a corner of
                for (dr in -1..0) for (dg in -1..0) for (db in -1..0) {
                    val r0 = r + dr; val g0 = g + dg; val b0 = b + db
                    if (r0 !in 0 until side || g0 !in 0 until side || b0 !in 0 until side) continue
                    val cell = (b0 * side + g0) * side + r0
                    if (!used[cell]) continue
                    cornersOf(cell)
                    val a = (-dr) * 4 + (-dg) * 2 + (-db)
                    val cellBase = cell * BLOCK
                    diagonal += block[cellBase + triangle(a, a)]
                    for (c2 in 0..7) {
                        if (c2 == a) continue
                        val m = block[cellBase + if (a <= c2) triangle(a, c2) else triangle(c2, a)]
                        if (m == 0.0) continue
                        val j = at[c2] * 3
                        for (ch in 0..2) off[ch] += m * x[j + ch]
                    }
                }

                var degree = 0
                for ((dr, dg, db) in NEIGHBOURS) {
                    val nr = r + dr; val ng = g + dg; val nb = b + db
                    if (nr !in 0 until size || ng !in 0 until size || nb !in 0 until size) continue
                    degree++
                    val j = index(nr, ng, nb) * 3
                    for (ch in 0..2) off[ch] -= smooth * x[j + ch]
                }
                diagonal += smooth * degree
                if (diagonal <= 0.0) continue

                val identity = doubleArrayOf(
                    r.toDouble() / (size - 1),
                    g.toDouble() / (size - 1),
                    b.toDouble() / (size - 1),
                )
                for (ch in 0..2) {
                    val free = (rhs[base + ch] - off[ch]) / diagonal
                    // The deviation may not take the value outside 0..1
                    val bounded = free.coerceIn(-identity[ch], 1.0 - identity[ch])
                    if (sweep == SWEEPS - 1 && bounded != free) held++
                    x[base + ch] += RELAX * (bounded - x[base + ch])
                    x[base + ch] = x[base + ch].coerceIn(-identity[ch], 1.0 - identity[ch])
                }
            }
        }
        return held
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Adobe/Resolve .cube text — readable by Lightroom, Capture One, Darktable, Resolve. */
    fun toCubeText(title: String, grid: FloatArray = build()): String = buildString {
        appendLine("# Generated by Reshipi from an in-camera RAW conversion")
        appendLine("# Measured coverage: ${(coverage() * 100).toInt()}% of the colour cube")
        appendLine("TITLE \"${title.replace('"', '\'')}\"")
        appendLine("LUT_3D_SIZE $size")
        appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        for (i in 0 until nodes) {
            appendLine(
                "%.6f %.6f %.6f".format(
                    java.util.Locale.US, grid[i * 3], grid[i * 3 + 1], grid[i * 3 + 2],
                )
            )
        }
    }

    private companion object {
        /** 36 weight products plus 8 corners of 3 channels. */
        const val BLOCK = 36 + 24

        /** Share of the average node's evidence below which it is not a measurement. */
        const val THIN_NODE = 0.02

        /** How hard neighbouring nodes are held together, relative to the data. */
        const val SMOOTH = 0.05

        /** How hard an unmeasured node is pulled back to leaving the colour alone. */
        const val PULL = 0.002

        const val MAX_STEPS = 120
        const val TOLERANCE = 1e-8

        /** Projected sweeps run after the solve to settle the bounds. */
        const val SWEEPS = 12

        /** Over-relaxation for those sweeps; under 2 to converge at all. */
        const val RELAX = 1.6

        val NEIGHBOURS = listOf(
            Triple(-1, 0, 0), Triple(1, 0, 0),
            Triple(0, -1, 0), Triple(0, 1, 0),
            Triple(0, 0, -1), Triple(0, 0, 1),
        )
    }
}
