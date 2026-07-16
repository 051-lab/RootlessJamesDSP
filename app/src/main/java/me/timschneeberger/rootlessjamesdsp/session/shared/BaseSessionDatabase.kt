package me.timschneeberger.rootlessjamesdsp.session.shared

import android.content.Context
import android.os.Process.myUid
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import timber.log.Timber

abstract class BaseSessionDatabase(protected val context: Context) {

    private val sessions = hashMapOf<Int, IEffectSession>()
    val sessionList: HashMap<Int, IEffectSession>
        @Synchronized get() = HashMap(sessions)
    private var isDisposing = false
    private val changeCallbacks = mutableListOf<OnSessionChangeListener>()
    private var excludedUids = emptySet<Int>()

    protected open val excludedPackages = arrayOf(
        context.packageName
    )
    protected abstract fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean
    protected abstract fun shouldAddSession(id: Int, uid: Int, packageName: String): Boolean
    protected abstract fun createSession(id: Int, uid: Int, packageName: String): IEffectSession?
    protected abstract fun onSessionRemoved(item: IEffectSession)

    @Synchronized fun destroy()
    {
        isDisposing = true
        clearSessions()
    }

    @Synchronized fun clearSessions(){
        sessions.forEach { (_, session) -> onSessionRemoved(session) }
        sessions.clear()
    }

    @Synchronized fun update(dump: ISessionInfoDump)
    {
        if(isDisposing) {
            Timber.d("update: SessionDatabase is disposing; ignoring dump")
            return
        }

        val removedSessions = sessions.filter {
            !dump.sessions.contains(it.key)
        }
        val addedSessions = dump.sessions.filter {
            !sessions.contains(it.key) && !excludedUids.contains(it.value.uid)
        }

        addedSessions.forEach next@ {
            val sid = it.key
            val data = it.value
            val name = context.packageManager.getNameForUid(it.value.uid)
            if (data.uid == myUid() || excludedPackages.contains(name)) {
                Timber.d("Skipped session $sid due to package name $name ($data)")
                return@next
            }
            if (sid == 0) {
                Timber.w("Session 0 skipped ($data)")
                return@next
            }

            if(shouldAcceptSessionDump(sid, data)) {
                addSession(sid, data.uid, data.packageName)
            }
        }

        removedSessions.forEach { removeSession(it.key) }
    }

    @Synchronized fun addSession(sid: Int, uid: Int, packageName: String, replace: Boolean = false){
        if(!shouldAddSession(sid, uid, packageName)) {
            return
        }

        if(excludedUids.contains(uid)) {
            Timber.d("Rejected session $sid from excluded uid $uid ($packageName)")
            return
        }

        if(replace) {
            // Remove old sessions from package
            sessions
                .filter { it.value.packageName == packageName }
                .keys
                .forEach(::removeSession)
            notifyChanged()
        }

        Timber.d("Found new session: sid=$sid; $packageName")
        sessions[sid] = createSession(sid, uid, packageName) ?: return
        Timber.d("Successfully added session $sid")

        notifyChanged()
    }

    @Synchronized fun removeSession(sid: Int) {
        sessions[sid]?.let { it ->
            Timber.d("Removed session: session ${sid}; data: $it")
            onSessionRemoved(it)
            sessions.remove(sid)
            notifyChanged()
        }
    }

    @Synchronized fun setExcludedUids(uids: Array<Int>) {
        excludedUids = uids.toSet()

        val excludedSessions = sessions.filter {
            excludedUids.contains(it.value.uid)
        }
        val notify = excludedSessions.isNotEmpty()
        excludedSessions.forEach { (_, session) -> onSessionRemoved(session) }
        excludedSessions.map { it.key }.forEach { sid -> sessions.remove(sid) }
        if(notify)
            notifyChanged()
    }

    @Synchronized fun registerOnSessionChangeListener(changeListener: OnSessionChangeListener) {
        changeCallbacks.add(changeListener)
        changeListener.onSessionChanged(HashMap(sessions))
    }

    @Synchronized fun unregisterOnSessionChangeListener(changeListener: OnSessionChangeListener) {
        changeCallbacks.remove(changeListener)
    }

    private fun notifyChanged() {
        changeCallbacks.toList().forEach { it.onSessionChanged(HashMap(sessions)) }
    }

    interface OnSessionChangeListener {
        fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>)
    }
}
