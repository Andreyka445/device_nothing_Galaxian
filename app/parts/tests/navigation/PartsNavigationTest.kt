// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.galaxian

import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.lineageos.settings.galaxian.adaptive.AdaptiveService

private const val PAGE = "org.lineageos.settings.galaxian.PAGE"
private const val PENDING_REVIEW = "pending_review"
private val proposalKey = AdaptiveService.EXTRA_PROPOSAL_ID
private val pages = listOf("cpu", "gpu", "memory", "battery", "temperature", "adaptive")
private var cases = 0

private fun test(name: String, body: () -> Unit) {
    UserHandle.currentUser = UserHandle.USER_SYSTEM
    Toast.shown = 0
    ScreenProbe.reset()
    try { body() } catch (error: Throwable) { throw AssertionError(name, error) }
    cases++
}
private fun expect(condition: Boolean, detail: String) { check(condition) { detail } }
private fun home(intent: Intent = Intent(), saved: Bundle? = null) = PartsActivity().apply {
    setIntent(intent); performCreate(saved)
    if (finishCount == 0 && launched.isEmpty()) performResume()
    render()
}
private fun child(route: String?, review: String? = null, saved: Bundle? = null) = PartsSubActivity().apply {
    setIntent(Intent().apply { if (route != null) putExtra(PAGE, route); if (review != null) putExtra(proposalKey, review) })
    performCreate(saved)
    if (finishCount == 0) performResume()
    render()
}
private fun request(id: String? = null) = Intent().apply {
    action = PartsActivity.ACTION_OPEN_ADAPTIVE
    data = "galaxian-parts://adaptive/${id ?: "monitor"}"
    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (id != null) putExtra(proposalKey, id)
}
private fun ComponentActivity.onlyLaunch(): Intent = launched.single()
private fun assertInternalPage(intent: Intent, page: String, id: String? = null) {
    expect(intent.destination == PartsSubActivity::class.java, "Destination must be the internal page Activity")
    expect(intent.getStringExtra(PAGE) == page, "Wrong page")
    expect(intent.getStringExtra(proposalKey) == id, "Proposal identity changed")
    expect(intent.flags == 0, "Internal page must stay in the existing task")
    expect(intent.action == null && intent.data == null, "External action/URI leaked into internal navigation")
    expect(intent.extraKeys() == setOfNotNull(PAGE, proposalKey.takeIf { id != null }), "Unexpected intent extras")
}

fun main() {
    test("ordinary Home opens no child") {
        val activity = home()
        expect(ScreenProbe.page == "home", "Home not rendered")
        expect(activity.launched.isEmpty() && activity.finishCount == 0, "Unexpected navigation")
    }
    for (page in pages) {
        test("Home launches $page in the current task") {
            val activity = home()
            ScreenProbe.navigate!!(page)
            assertInternalPage(activity.onlyLaunch(), page)
        }
        test("$page renders and its toolbar finishes only that Activity") {
            val activity = child(page)
            expect(ScreenProbe.page == page, "Wrong screen rendered")
            expect(activity.launched.isEmpty(), "Subpage must not launch another Activity")
            ScreenProbe.back!!()
            expect(activity.finishCount == 1, "Toolbar must finish the subpage")
        }
    }
    for (route in listOf("", "other", "adaptive/approve")) {
        test("Home ignores unknown route '$route'") {
            val activity = home()
            ScreenProbe.navigate!!(route)
            expect(activity.launched.isEmpty(), "Unknown destination was accepted")
            ScreenProbe.navigate!!("cpu")
            assertInternalPage(activity.onlyLaunch(), "cpu")
        }
    }
    test("missing child route finishes without content") {
        val activity = child(null)
        expect(activity.finishCount == 1 && activity.content == null, "Missing route must not silently open a page")
    }
    test("invalid child route finishes without content") {
        val activity = child("cpu/execute")
        expect(activity.finishCount == 1 && activity.content == null, "Invalid route must not render")
    }
    test("owner restriction applies to Home including notification intents") {
        UserHandle.currentUser = 10
        val activity = home(request("forbidden"))
        expect(activity.finishCount == 1 && activity.content == null && activity.launched.isEmpty(), "Secondary user entered Home")
        expect(Toast.shown == 1, "Owner restriction was not explained")
    }
    test("owner restriction applies independently to internal pages") {
        UserHandle.currentUser = 10
        val activity = child("adaptive", "forbidden")
        expect(activity.finishCount == 1 && activity.content == null, "Secondary user entered internal page")
    }
    test("new notification is ignored for a secondary user") {
        val activity = home()
        UserHandle.currentUser = 10
        activity.performNewIntent(request("forbidden"))
        expect(activity.launched.isEmpty(), "New intent bypassed owner restriction")
    }
    test("cold proposal notification forwards only its exact ID") {
        val original = request("proposal-A").putExtra("approve", "true").putExtra("target_frequency", "9999999")
        val activity = home(original)
        assertInternalPage(activity.onlyLaunch(), "adaptive", "proposal-A")
        expect(activity.intent.action == null && activity.intent.data == null && !activity.intent.hasExtra(proposalKey), "Home retained the consumed request")
        expect(original.action == PartsActivity.ACTION_OPEN_ADAPTIVE && original.getStringExtra(proposalKey) == "proposal-A", "Incoming immutable request was mutated")
    }
    test("monitoring notification opens Adaptive without selecting a proposal") {
        val activity = home(request())
        assertInternalPage(activity.onlyLaunch(), "adaptive")
    }
    test("ordinary intent cannot inject a proposal dialog") {
        val activity = home(Intent().putExtra(proposalKey, "not-a-review-request"))
        expect(activity.launched.isEmpty(), "ID extra alone was treated as a deep link")
    }
    test("warm proposal notification opens exactly the requested proposal") {
        val activity = home()
        activity.performNewIntent(request("proposal-B"))
        assertInternalPage(activity.onlyLaunch(), "adaptive", "proposal-B")
    }
    test("a newer notification keeps its own identity") {
        val activity = home(request("old-A"))
        activity.performNewIntent(request("new-B"))
        expect(activity.launched.size == 2, "Distinct notification delivery lost")
        assertInternalPage(activity.launched[0], "adaptive", "old-A")
        assertInternalPage(activity.launched[1], "adaptive", "new-B")
    }
    test("restored Home does not replay the original notification") {
        val activity = home(request("old-A"), Bundle())
        expect(activity.launched.isEmpty(), "Recreation duplicated the child Activity")
        activity.performNewIntent(request("new-B"))
        assertInternalPage(activity.onlyLaunch(), "adaptive", "new-B")
    }
    test("saving Home after launch does not recreate its child manually") {
        val first = home(request("A"))
        val restored = home(request("A"), first.performSave())
        expect(restored.launched.isEmpty(), "Android-restored child would be duplicated")
    }
    test("Adaptive receives the exact proposal ID for review, not approval") {
        val activity = child("adaptive", "A")
        expect(ScreenProbe.reviewId == "A", "Review ID was lost")
        expect(activity.launched.isEmpty() && activity.finishCount == 0, "Review request performed navigation")
    }
    test("non-Adaptive pages ignore proposal extras") {
        val activity = child("gpu", "A")
        expect(ScreenProbe.page == "gpu" && ScreenProbe.reviewId == null, "Proposal leaked into unrelated page")
        expect(activity.performSave().getString(PENDING_REVIEW) == null, "Unrelated page persisted a proposal")
    }
    test("unconsumed review survives Activity recreation") {
        val first = child("adaptive", "A")
        val saved = first.performSave()
        ScreenProbe.reset()
        val restored = child("gpu", "B", saved)
        expect(ScreenProbe.page == "adaptive" && ScreenProbe.reviewId == "A", "Saved route/review was replaced by stale Intent fields")
        expect(restored.finishCount == 0, "Valid restoration was rejected")
    }
    test("consumed review does not reopen after Activity recreation") {
        val first = child("adaptive", "A")
        ScreenProbe.consumeReview!!()
        val saved = first.performSave()
        ScreenProbe.reset()
        child("adaptive", "A", saved)
        expect(ScreenProbe.page == "adaptive" && ScreenProbe.reviewId == null, "Consumed request was replayed")
    }
    test("rendering after consumption no longer resupplies the review ID") {
        val activity = child("adaptive", "A")
        ScreenProbe.consumeReview!!()
        activity.render()
        expect(ScreenProbe.reviewId == null, "Consumed review remains an input to the screen")
    }
    test("saved invalid route cannot fall back to a valid Intent route") {
        val saved = Bundle().apply { putString(PAGE, "invalid") }
        val activity = child("cpu", saved = saved)
        expect(activity.finishCount == 1 && activity.content == null, "Corrupt saved route was silently ignored")
    }
    test("saved missing route cannot resurrect an Intent deep link") {
        val activity = child("adaptive", "old-A", Bundle())
        expect(activity.finishCount == 1 && activity.content == null, "Missing saved route reprocessed old intent")
    }
    test("rapid Home taps cannot stack multiple pages") {
        val activity = home()
        val navigate = ScreenProbe.navigate!!
        navigate("cpu"); navigate("cpu"); navigate("gpu")
        assertInternalPage(activity.onlyLaunch(), "cpu")
        activity.performPause(); activity.performResume()
        navigate("gpu")
        expect(activity.launched.size == 2, "Launch guard did not reset for the next visit")
        assertInternalPage(activity.launched[1], "gpu")
    }
    test("Home tap guard never drops an incoming notification") {
        val activity = home()
        ScreenProbe.navigate!!("gpu")
        activity.performNewIntent(request("latest-B"))
        expect(activity.launched.size == 2, "Notification was suppressed by the click guard")
        assertInternalPage(activity.launched[1], "adaptive", "latest-B")
    }
    println("Parts native Activity navigation: $cases scenarios PASS")
}
