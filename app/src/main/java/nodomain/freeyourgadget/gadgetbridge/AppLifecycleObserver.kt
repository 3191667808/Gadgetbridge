package nodomain.freeyourgadget.gadgetbridge

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner


class AppLifecycleObserver : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        AppEventBus.emit(AppLifecycleEvent.AppInForeground)
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        AppEventBus.emit(AppLifecycleEvent.AppInBackground)
    }

}