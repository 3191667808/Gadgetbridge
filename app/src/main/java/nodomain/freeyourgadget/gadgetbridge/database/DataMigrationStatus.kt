package nodomain.freeyourgadget.gadgetbridge.database

data class DataMigrationStatus(
    var done: Boolean = false,
    var processed: Long = 0,
    var total: Long = 0,
    var position: Long = -1,
)
