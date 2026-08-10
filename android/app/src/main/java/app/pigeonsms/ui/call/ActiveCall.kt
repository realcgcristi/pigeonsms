package app.pigeonsms.ui.call

object ActiveCall {
    @Volatile private var hangup: (() -> Unit)? = null

    fun register(onHangup: () -> Unit) {
        hangup = onHangup
    }

    fun unregister() {
        hangup = null
    }

    fun requestHangup() {
        hangup?.invoke()
    }
}
