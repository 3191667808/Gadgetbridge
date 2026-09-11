/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.gloryfitpro

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.devices.gloryfit.GloryFitActivitySampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser

/**
 * The GloryFit Pro watches report steps and heart rate per minute but no intensity of their own,
 * so the activity chart - which plots exactly that - would stay empty outside sleep. This
 * derives one.
 *
 * A minute that had steps is scored on those steps, at the hundred-steps-is-full-scale rate the
 * rest of Gadgetbridge assumes when it reads an intensity. A minute without a single step is
 * scored on heart rate instead, as a percentage of the heart rate reserve (Karvonen): the resting
 * beat is taken from the lowest reading in the window, which for a day that includes a night is
 * the wearer's own resting rate rather than a guess, and the ceiling is the usual 220 - age. That
 * way the chart still shows exertion the step counter cannot see, without heart rate quietly
 * promoting a near-idle minute into an activity session.
 *
 * The value is derived, not measured: it says how hard the wearer was working relative to their
 * own range, and is not comparable to the intensity a device reports natively.
 */
open class GloryFitProActivitySampleProvider(private val device: GBDevice, session: DaoSession) :
    GloryFitActivitySampleProvider(device, session) {

    /** Intensity is kept as a percentage so it survives the sample's integer field. */
    override fun normalizeIntensity(rawIntensity: Int): Float {
        return rawIntensity / 100f
    }

    override fun getAllActivitySamples(
        timestampFrom: Int,
        timestampTo: Int
    ): MutableList<GenericActivitySample> {
        val samples = fillGaps(super.getAllActivitySamples(timestampFrom, timestampTo))
        // The sleep overlay ran inside the call above, before the filled minutes existed, so run
        // it again - otherwise a minute the watch happened not to record would punch a hole in
        // the middle of a sleep session.
        overlaySleep(samples, timestampFrom, timestampTo)
        deriveIntensity(samples)
        return samples
    }

    /**
     * The watch only writes a record for a minute in which something happened, so a quiet stretch
     * leaves no sample at all rather than a sample reading zero. Anything downstream then cannot
     * tell a pause from a hole: the chart interpolates straight across it, and the session
     * detection in StepAnalysis never sees the idle minutes it would split an activity on, so
     * sessions run on through the gaps and the day's active time comes out too high.
     *
     * Fill the quiet minutes in with explicit zeroes. Only between the first and last real sample
     * - before and after those the watch may simply not have been worn, which is a different
     * thing from being still.
     */
    private fun fillGaps(samples: MutableList<GenericActivitySample>): MutableList<GenericActivitySample> {
        if (samples.size < 2) return samples
        val firstMinute = samples.first().timestamp / 60
        val lastMinute = samples.last().timestamp / 60
        if (lastMinute - firstMinute > MAX_FILL_MINUTES) return samples

        val present = HashSet<Int>(samples.size * 2)
        for (sample in samples) present.add(sample.timestamp / 60)

        // A long silence only means the watch is off the wrist while it is measuring of its own
        // accord. With the automatic monitors switched off the only thing left to break a
        // silence is movement, and then a gap says "sat still" - see canDetectNotWorn.
        val markNotWorn = canDetectNotWorn()
        // The day's step total is kept in a bookkeeping sample at the very end of the day, so
        // the last stretch of every day is a gap by construction - the watch simply has not
        // written those minutes out yet. Never read that one as a removal.
        val lastRealMinute = present.filter { it != lastMinute }.maxOrNull() ?: firstMinute
        var gapStart = firstMinute
        for (minute in firstMinute + 1..lastMinute) {
            if (!present.contains(minute)) continue
            val gap = (minute - gapStart).toInt()
            val kind = if (markNotWorn && gap > NOT_WORN_GAP_MINUTES && minute <= lastRealMinute) {
                ActivityKind.NOT_WORN
            } else {
                ActivityKind.UNKNOWN
            }
            for (empty in gapStart + 1 until minute) {
                val sample = GenericActivitySample()
                sample.provider = this
                sample.timestamp = empty * 60
                sample.steps = 0
                sample.rawIntensity = 0
                sample.rawKind = kind.code
                samples.add(sample)
            }
            gapStart = minute
        }
        samples.sortBy { it.timestamp }
        return samples
    }

    /**
     * Whether a gap in the samples can be read as the watch being off the wrist.
     *
     * The watch has no worn/not-worn flag. What it does have is a habit of measuring heart rate
     * and blood oxygen on its own schedule, and that stops dead when there is no wrist under the
     * sensor - measured on hardware, 51 health frames in 16 minutes worn against 0 in 15 minutes
     * on a table, with the keepalive ticking on in both. But both monitors are settings the
     * wearer can switch off, and with them off the only remaining records are step-driven, so a
     * silence then means "did not move" rather than "not worn" - also measured, 0 frames in 5
     * minutes of sitting still with the watch on. So the inference is only available while both
     * are enabled.
     */
    private fun canDetectNotWorn(): Boolean {
        val prefs = GBApplication.getDevicePrefs(device)
        return prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HEARTRATE_AUTOMATIC_ENABLE, false) &&
                prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, false)
    }

    private fun deriveIntensity(samples: List<GenericActivitySample>) {
        val restingHeartRate = samples.asSequence()
            .map { it.heartRate }
            .filter { it in MIN_HEART_RATE..MAX_HEART_RATE }
            .minOrNull()
            ?.coerceIn(RESTING_FLOOR, RESTING_CEILING)
            ?: RESTING_DEFAULT
        val age = ActivityUser().age
        val maxHeartRate = if (age in 1..120) AGE_FORMULA_BASE - age else MAX_HEART_RATE_DEFAULT
        val reserve = (maxHeartRate - restingHeartRate).coerceAtLeast(MIN_RESERVE)

        for (sample in samples) {
            // Sleep stages carry their own fixed heights in the chart, flagged by a negative
            // intensity - leave those alone.
            if (ActivityKind.isSleep(sample.kind)) continue

            // Which signal drives the intensity depends on whether the minute had movement,
            // and that is not cosmetic. StepAnalysis decides a minute is part of an activity
            // session on "more than N steps, or intensity above N/100 with at least one step",
            // so it reads intensity as a stand-in for steps per minute. Letting heart rate set
            // it on a minute that had a handful of steps drags that minute into a session and
            // makes the day's active time run away - and it also defeats the setting the user
            // has for N, since the heart rate half of the test does not know about it.
            //
            // So: a minute with movement is scored on its steps, on the scale StepAnalysis
            // assumes. A minute without movement is scored on heart rate, where it says
            // something the steps cannot and where it can never pull a session together on its
            // own, because both halves of that test require at least one step.
            val heartRate = sample.heartRate
            val percent = if (sample.steps > 0) {
                (sample.steps * 100 / STEPS_AT_FULL_SCALE).coerceAtMost(100)
            } else if (heartRate in MIN_HEART_RATE..MAX_HEART_RATE) {
                ((heartRate - restingHeartRate) * 100 / reserve).coerceIn(0, 100)
            } else {
                0
            }
            sample.rawIntensity = percent
            // Only movement makes a minute activity. An elevated heart rate on its own raises the
            // intensity, which is what the chart draws, but sitting still with a fast pulse is
            // not activity and should not be labelled as such.
            if (sample.steps > 0) {
                sample.rawKind = ActivityKind.ACTIVITY.code
            }
        }
    }

    companion object {
        private const val MIN_HEART_RATE = 25
        private const val MAX_HEART_RATE = 250

        /** Bounds on what the lowest reading in a window is allowed to be taken for. */
        private const val RESTING_FLOOR = 40
        private const val RESTING_CEILING = 70
        private const val RESTING_DEFAULT = 60

        private const val AGE_FORMULA_BASE = 220
        private const val MAX_HEART_RATE_DEFAULT = 190
        private const val MIN_RESERVE = 40

        /** Steps in a minute that count as working at full tilt - a brisk walk or better. */
        private const val STEPS_AT_FULL_SCALE = 100

        /** Beyond this the caller is asking for a range no chart draws minute by minute. */
        private const val MAX_FILL_MINUTES = 3 * 24 * 60

        /**
         * Silence longer than this reads as the watch being off the wrist. Garmin uses ten
         * minutes for the same purpose; this is more cautious because the longest gap measured
         * on this watch while it was demonstrably worn was itself ten minutes, and a wearer can
         * stretch that by setting a slower measurement interval. Removals worth marking - a
         * charge, a forgotten morning - run far longer than fifteen minutes anyway.
         */
        private const val NOT_WORN_GAP_MINUTES = 15
    }
}
