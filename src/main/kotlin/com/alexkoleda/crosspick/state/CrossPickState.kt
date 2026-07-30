package com.alexkoleda.crosspick.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persists CrossPick's last-used inputs (source repo URL, branch, initial SHA) and the
 * set of source commit SHAs that have already been transferred (or deliberately
 * skipped) into each target repository, so the tool window can be repopulated across
 * IDE restarts and so the user is not offered to re-transfer commits that already made
 * it across or that they've explicitly chosen to skip.
 */
@State(name = "CrossPickState", storages = [Storage("crosspick.xml")])
@Service(Service.Level.PROJECT)
class CrossPickState : PersistentStateComponent<CrossPickState.Data> {

    /**
     * Serializable state payload. [transferred] and [skipped] are both keyed by
     * "sourceUrl|targetRepoRoot" and map to the list of fully-qualified source SHAs
     * already cherry-picked (or deliberately skipped) for that source/target pair.
     */
    class Data {
        var lastSourceUrl: String = ""
        var lastSourceBranch: String = ""
        var lastInitialSha: String = ""
        var transferred: MutableMap<String, MutableList<String>> = mutableMapOf()
        var skipped: MutableMap<String, MutableList<String>> = mutableMapOf()
    }

    private var myState = Data()

    override fun getState(): Data = myState

    override fun loadState(state: Data) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /** Returns true if [sha] was already recorded as transferred for the given [key]. */
    fun isTransferred(key: String, sha: String): Boolean =
        myState.transferred[key]?.contains(sha) == true

    /** Records [sha] as transferred for the given [key], avoiding duplicate entries. */
    fun markTransferred(key: String, sha: String) {
        val list = myState.transferred.getOrPut(key) { mutableListOf() }
        if (!list.contains(sha)) {
            list.add(sha)
        }
    }

    /** Returns true if [sha] was already recorded as skipped for the given [key]. */
    fun isSkipped(key: String, sha: String): Boolean =
        myState.skipped[key]?.contains(sha) == true

    /** Records [sha] as deliberately skipped for the given [key], avoiding duplicates. */
    fun markSkipped(key: String, sha: String) {
        val list = myState.skipped.getOrPut(key) { mutableListOf() }
        if (!list.contains(sha)) {
            list.add(sha)
        }
    }

    /** Removes [sha] from both the transferred and skipped records for the given [key]. */
    fun clearRowState(key: String, sha: String) {
        myState.transferred[key]?.remove(sha)
        myState.skipped[key]?.remove(sha)
    }
}
