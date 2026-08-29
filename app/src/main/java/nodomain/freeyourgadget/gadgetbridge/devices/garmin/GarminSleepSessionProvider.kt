package nodomain.freeyourgadget.gadgetbridge.devices.garmin

import nodomain.freeyourgadget.gadgetbridge.devices.GarminNapSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GarminSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SleepSessionProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GarminNapSample
import nodomain.freeyourgadget.gadgetbridge.entities.GarminSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession
import nodomain.freeyourgadget.gadgetbridge.model.SleepStage
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.fieldDefinitions.FieldDefinitionSleepStage

class GarminSleepSessionProvider(
    private val device: GBDevice,
    private val session: DaoSession
) : SleepSessionProvider {
    override fun getSleepSessions(tsFrom: Int, tsTo: Int): List<SleepSession> {
        val sleepSessions = ArrayList<SleepSession>()

        val fromMillis: Long = (tsFrom - LOOK_BACK_SECONDS) * 1000L
        val toMillis: Long = (tsTo + LOOK_AHEAD_SECONDS) * 1000L

        // Get sleep / start events
        val eventSampleProvider = GarminEventSampleProvider(device, session)
        val sleepEventSamples = eventSampleProvider.getSleepEvents(fromMillis, toMillis)
        if (!sleepEventSamples.isEmpty()) {
            val sleepStagesSampleProvider = GarminSleepStageSampleProvider(device, session)

            var sessionStart = -1L
            var sessionEnd = -1L
            for (event in sleepEventSamples) {
                when (event.eventType) {
                    0 -> {
                        sessionStart = event.timestamp
                        sessionEnd = -1L
                    }

                    1 -> sessionEnd = event.timestamp
                }

                if (sessionStart > 0 && sessionEnd > 0) {
                    val stageSamples = sleepStagesSampleProvider.getAllSamples(sessionStart, sessionEnd + 1L)
                    val stages = ArrayList<SleepStage>(stageSamples.size)
                    if (!stageSamples.isEmpty()) {
                        // GarminStageSamples are upper bound of the stage
                        var previousTs = sessionStart
                        for (sample in stageSamples) {
                            stages.add(
                                SleepStage(
                                    fiSleepToActivityKind(sample),
                                    previousTs,
                                    sample.timestamp
                                )
                            )
                            previousTs = sample.timestamp
                        }
                    } else {
                        // No stages sent by the watch - add a single stage for the entire duration
                        stages.add(SleepStage(ActivityKind.LIGHT_SLEEP, sessionStart, sessionEnd))
                    }

                    sleepSessions.add(SleepSession(stages))

                    sessionStart = -1L
                    sessionEnd = -1L
                }
            }
        }

        // Naps become their own short sessions
        // TODO: Dedicated nap support in Gb?
        val napSampleProvider = GarminNapSampleProvider(device, session)
        val napSamples: List<GarminNapSample> = napSampleProvider.getAllSamples(fromMillis, toMillis)
        for (napSample in napSamples) {
            sleepSessions.add(
                SleepSession(
                    listOf(
                        SleepStage(ActivityKind.LIGHT_SLEEP, napSample.timestamp, napSample.endTimestamp)
                    )
                )
            )
        }

        return sleepSessions
            .filter { (it.startTime / 1000L) in tsFrom..<tsTo }
            .sortedBy { it.startTime }
    }

    companion object {
        private const val LOOK_BACK_SECONDS: Int = 12 * 60 * 60
        private const val LOOK_AHEAD_SECONDS: Int = 12 * 60 * 60

        fun fiSleepToActivityKind(stageSample: GarminSleepStageSample): ActivityKind {
            val sleepStage = FieldDefinitionSleepStage.fromId(stageSample.stage) ?: return ActivityKind.UNKNOWN

            return when (sleepStage) {
                nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.SleepStage.AWAKE -> ActivityKind.AWAKE_SLEEP
                nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.SleepStage.LIGHT -> ActivityKind.LIGHT_SLEEP
                nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.SleepStage.DEEP -> ActivityKind.DEEP_SLEEP
                nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.SleepStage.REM -> ActivityKind.REM_SLEEP
                else -> ActivityKind.SLEEP_ANY
            }
        }
    }
}
