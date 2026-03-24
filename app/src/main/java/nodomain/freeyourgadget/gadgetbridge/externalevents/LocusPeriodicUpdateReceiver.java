package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocusPeriodicUpdateReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(LocusPeriodicUpdateReceiver.class);
    public static final String ACTION_LOCUS_STATE_UPDATE = "nodomain.freeyourgadget.gadgetbridge.LOCUS_STATE_UPDATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        LOG.debug("Received periodic state update in Manifest from Locus Map");
        if (intent == null) return;
        
        Intent internalIntent = new Intent(ACTION_LOCUS_STATE_UPDATE);
        if (intent.getExtras() != null) {
            internalIntent.putExtras(intent.getExtras());
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(internalIntent);
    }
}
