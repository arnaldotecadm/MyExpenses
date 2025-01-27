package org.totschnig.myexpenses.testutils

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withParentIndex
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.adapter.IdHolder
import org.totschnig.myexpenses.delegate.TransactionDelegate.OperationType
import org.totschnig.myexpenses.model.CrStatus
import org.totschnig.myexpenses.viewmodel.data.PaymentMethod

fun withMethod(label: String): Matcher<Any> =
        object : BoundedMatcher<Any, PaymentMethod>(PaymentMethod::class.java) {
            override fun matchesSafely(myObj: PaymentMethod): Boolean {
                return myObj.label().equals(label)
            }

            override fun describeTo(description: Description) {
                description.appendText("with method '${label}'")
            }
        }

fun withStatus(status: CrStatus): Matcher<Any> =
        object : BoundedMatcher<Any, CrStatus>(CrStatus::class.java) {
            override fun matchesSafely(myObj: CrStatus): Boolean {
                return myObj == status
            }

            override fun describeTo(description: Description) {
                description.appendText("with status '${status.name}'")
            }
        }

fun withAccount(content: String): Matcher<Any> =
        object : BoundedMatcher<Any, IdHolder>(IdHolder::class.java) {
            override fun matchesSafely(myObj: IdHolder): Boolean {
                return myObj.toString() == content
            }

            override fun describeTo(description: Description) {
                description.appendText("with label '$content'")
            }
        }

fun withOperationType(type: Int): Matcher<Any> =
    object : BoundedMatcher<Any, OperationType>(OperationType::class.java) {
        override fun matchesSafely(myObj: OperationType): Boolean {
            return myObj.type == type
        }

        override fun describeTo(description: Description) {
            description.appendText("with operation type '$type'")
        }
    }

/**
 * https://stackoverflow.com/a/63330069/1199911
 * @param parentViewId the resource id of the parent [View].
 * @param position the child index of the [View] to match.
 * @return a [Matcher] that matches the child [View] which has the given [position] within the specified parent.
 */
fun withPositionInParent(parentViewId: Int, position: Int): Matcher<View> {
    return allOf(withParent(withId(parentViewId)), withParentIndex(position))
}

fun toolbarTitle(): ViewInteraction = onView(allOf(instanceOf(TextView::class.java), withParent(ViewMatchers.withId(R.id.toolbar))))

//Espresso recorder
fun childAtPosition(
    parentMatcher: Matcher<View>, position: Int
): Matcher<View> {

    return object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("Child at position $position in parent ")
            parentMatcher.describeTo(description)
        }

        public override fun matchesSafely(view: View): Boolean {
            val parent = view.parent
            return parent is ViewGroup && parentMatcher.matches(parent)
                    && view == parent.getChildAt(position)
        }
    }
}