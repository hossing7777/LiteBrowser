package com.litebrowser

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

object TerminalManager : TerminalSessionClient {
    val sessions = mutableListOf<TerminalSession>()
    private var currentClient: TerminalSessionClient? = null

    fun setClient(client: TerminalSessionClient?) {
        currentClient = client
    }

    fun addSession(session: TerminalSession) {
        sessions.add(session)
    }

    fun removeSession(session: TerminalSession) {
        sessions.remove(session)
    }

    override fun onTextChanged(changedSession: TerminalSession?) { currentClient?.onTextChanged(changedSession) }
    override fun onTitleChanged(changedSession: TerminalSession?) { currentClient?.onTitleChanged(changedSession) }
    override fun onSessionFinished(finishedSession: TerminalSession?) {
        finishedSession?.let { removeSession(it) }
        currentClient?.onSessionFinished(finishedSession)
    }
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) { currentClient?.onCopyTextToClipboard(session, text) }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { currentClient?.onPasteTextFromClipboard(session) }
    override fun onBell(session: TerminalSession?) { currentClient?.onBell(session) }
    override fun onColorsChanged(session: TerminalSession?) { currentClient?.onColorsChanged(session) }
    override fun onTerminalCursorStateChange(state: Boolean) { currentClient?.onTerminalCursorStateChange(state) }
    override fun getTerminalCursorStyle(): Int? = currentClient?.getTerminalCursorStyle()
    override fun logError(tag: String?, message: String?) { currentClient?.logError(tag, message) }
    override fun logWarn(tag: String?, message: String?) { currentClient?.logWarn(tag, message) }
    override fun logInfo(tag: String?, message: String?) { currentClient?.logInfo(tag, message) }
    override fun logDebug(tag: String?, message: String?) { currentClient?.logDebug(tag, message) }
    override fun logVerbose(tag: String?, message: String?) { currentClient?.logVerbose(tag, message) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { currentClient?.logStackTraceWithMessage(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { currentClient?.logStackTrace(tag, e) }
}
