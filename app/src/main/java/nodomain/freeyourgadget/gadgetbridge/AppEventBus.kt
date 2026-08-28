package nodomain.freeyourgadget.gadgetbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

sealed interface Event

sealed interface AppConfigEvent : Event {
    object LanguageChanged : AppConfigEvent
    object ThemeChanged : AppConfigEvent
}

sealed interface AppLifecycleEvent : Event {
    object AppInForeground : AppLifecycleEvent
    object AppInBackground : AppLifecycleEvent
    object Quit : AppLifecycleEvent
}

object AppEventBus {
    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @JvmStatic
    fun emit(event: Event) {
        _events.tryEmit(event)
    }

    fun interface Listener {
        fun onEvent(event: Event)
    }

    @JvmStatic
    fun subscribe(listener: Listener): Job {
        return events.onEach { listener.onEvent(it) }.launchIn(scope)
    }

    @JvmStatic
    fun unsubscribe(job: Job) {
        job.cancel()
    }
}