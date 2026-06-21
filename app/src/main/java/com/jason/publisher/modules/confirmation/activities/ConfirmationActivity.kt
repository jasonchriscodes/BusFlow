package com.jason.publisher.modules.confirmation.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.main.model.ScheduleItem
import com.jason.publisher.main.utils.extractWorkIntervalsAndRunNames
import com.jason.publisher.main.utils.truncateUntilBreak
import com.jason.publisher.modules.`break`.activities.BreakActivity
import com.jason.publisher.modules.`break`.activities.TestBreakActivity
import com.jason.publisher.modules.map.activities.MapActivity
import com.jason.publisher.modules.map.activities.TestMapActivity
import com.jason.publisher.modules.map.utils.formatPanelLabel
import com.jason.publisher.modules.rep.activities.RepActivity
import com.jason.publisher.modules.rep.activities.TestRepActivity
import com.jason.publisher.modules.schedule.adapters.ScheduleAdapter
import com.jason.publisher.modules.schedule.widgets.StyledMultiColorTimeline
import com.jason.publisher.modules.signing.activities.SigningActivity
import com.jason.publisher.modules.signing.activities.TestSigningActivity
import com.varunest.sparkbutton.SparkButton
import com.varunest.sparkbutton.SparkEventListener

/**
 * Intermediary screen shown after "Start Route" / "Test Route" on ScheduleActivity, before
 * entering MapActivity / RepActivity / BreakActivity / SigningActivity (or their Test variants).
 * Reminds the driver to confirm the BDC and Hanover destination signage for the current schedule
 * before continuing, then forwards every extra it received on to whichever activity
 * ScheduleActivity already decided on, and relays that activity's result straight back.
 */
class ConfirmationActivity : AppCompatActivity() {

    private val targetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            setResult(result.resultCode, result.data)
            finish()
        }

    private var isTabulatedView = false

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirmation)

        val firstScheduleItem = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM", ScheduleItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("FIRST_SCHEDULE_ITEM")
        })?.firstOrNull()

        val fullScheduleData = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA", ScheduleItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("FULL_SCHEDULE_DATA")
        }) ?: arrayListOf()

        val tripLabel = intent.getStringExtra("BREAK_LABEL")
            ?: intent.getStringExtra("SIGNING_LABEL")
            ?: firstScheduleItem?.let { formatPanelLabel(it) }
            ?: "the current trip"

        setUpSignageChecklist(tripLabel, firstScheduleItem)
        setUpScheduleView(firstScheduleItem, fullScheduleData)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun setUpSignageChecklist(tripLabel: String, firstScheduleItem: ScheduleItem?) {
        val bdcSparkButton = findViewById<SparkButton>(R.id.bdcSparkButton)
        val hanoverSparkButton = findViewById<SparkButton>(R.id.hanoverSparkButton)
        val bdcRow = findViewById<View>(R.id.bdcRow)
        val hanoverRow = findViewById<View>(R.id.hanoverRow)
        val bdcLabelText = findViewById<TextView>(R.id.bdcLabelText)
        val hanoverLabelText = findViewById<TextView>(R.id.hanoverLabelText)
        val warningText = findViewById<TextView>(R.id.confirmationWarningText)
        val startTimeText = findViewById<TextView>(R.id.confirmationStartTimeText)

        bdcLabelText.text = "BDC Set to $tripLabel"
        hanoverLabelText.text = "Hanover Set to $tripLabel"
        warningText.text = "Please confirm BDC and Hanover before"
        startTimeText.text = firstScheduleItem?.startTime ?: "--:--"

        bdcRow.setOnClickListener { bdcSparkButton.performClick() }
        hanoverRow.setOnClickListener { hanoverSparkButton.performClick() }

        var signageConfirmed = false

        // Fires the instant a box is checked: lock both buttons so a second tap can't
        // un-check or race the in-flight spark animation.
        val onSignageChecked = {
            if (!signageConfirmed) {
                bdcSparkButton.isEnabled = false
                hanoverSparkButton.isEnabled = false
            }
        }
        // Fires once the spark animation finishes playing: that's the actual "delay"
        // before continuing on to the target activity.
        val onSignageAnimationEnd = {
            if (!signageConfirmed) {
                signageConfirmed = true
                launchTarget()
            }
        }

        bdcSparkButton.setEventListener(object : SparkEventListener {
            override fun onEvent(button: ImageView, buttonState: Boolean) {
                if (buttonState) onSignageChecked()
            }
            override fun onEventAnimationStart(button: ImageView, buttonState: Boolean) {}
            override fun onEventAnimationEnd(button: ImageView, buttonState: Boolean) {
                if (buttonState) onSignageAnimationEnd()
            }
        })
        hanoverSparkButton.setEventListener(object : SparkEventListener {
            override fun onEvent(button: ImageView, buttonState: Boolean) {
                if (buttonState) onSignageChecked()
            }
            override fun onEventAnimationStart(button: ImageView, buttonState: Boolean) {}
            override fun onEventAnimationEnd(button: ImageView, buttonState: Boolean) {
                if (buttonState) onSignageAnimationEnd()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bdcSparkButton.isChecked || hanoverSparkButton.isChecked) {
                    finish()
                } else {
                    Toast.makeText(
                        this@ConfirmationActivity,
                        "Please confirm BDC or Hanover before continuing",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun setUpScheduleView(firstScheduleItem: ScheduleItem?, fullScheduleData: List<ScheduleItem>) {
        val combined = if (firstScheduleItem != null && fullScheduleData.firstOrNull() != firstScheduleItem) {
            listOf(firstScheduleItem) + fullScheduleData
        } else {
            fullScheduleData
        }
        val untilNextBreak = combined.truncateUntilBreak()

        val recycler = findViewById<RecyclerView>(R.id.confirmationScheduleRecycler)
        val timelineContainer = findViewById<View>(R.id.confirmationTimelineContainer)
        val timelinePart1 = findViewById<StyledMultiColorTimeline>(R.id.confirmationTimelinePart1)
        val timelinePart2 = findViewById<StyledMultiColorTimeline>(R.id.confirmationTimelinePart2)
        val changeModeButton = findViewById<Button>(R.id.confirmationChangeModeButton)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ScheduleAdapter(untilNextBreak, isDarkMode = false)

        val (intervals, runNames) = untilNextBreak.extractWorkIntervalsAndRunNames()
        val itemsPerTimeline = kotlin.math.ceil(untilNextBreak.size / 2.0).toInt().coerceAtLeast(1)

        renderTimelinePart(
            timelinePart1,
            untilNextBreak.take(itemsPerTimeline),
            intervals.take(itemsPerTimeline),
            runNames.take(itemsPerTimeline)
        )
        renderTimelinePart(
            timelinePart2,
            untilNextBreak.drop(itemsPerTimeline),
            intervals.drop(itemsPerTimeline),
            runNames.drop(itemsPerTimeline)
        )
        timelinePart2.visibility = if (untilNextBreak.size > itemsPerTimeline) View.VISIBLE else View.GONE

        fun applyVisibility() {
            recycler.visibility = if (isTabulatedView) View.VISIBLE else View.GONE
            timelineContainer.visibility = if (isTabulatedView) View.GONE else View.VISIBLE
            changeModeButton.text = if (isTabulatedView) "Timeline View" else "Tabulated View"
        }
        applyVisibility()

        changeModeButton.setOnClickListener {
            isTabulatedView = !isTabulatedView
            applyVisibility()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun renderTimelinePart(
        timeline: StyledMultiColorTimeline,
        items: List<ScheduleItem>,
        intervals: List<Pair<String, String>>,
        runNames: List<String>
    ) {
        timeline.setScheduleData(items)
        timeline.setTimelineData(intervals, runNames)
        timeline.setBusStops(items.flatMap { it.busStops })
        timeline.setSingleLineMode(false)
    }

    private fun launchTarget() {
        val targetClass = when (intent.getStringExtra(EXTRA_TARGET)) {
            TARGET_MAP -> MapActivity::class.java
            TARGET_TEST_MAP -> TestMapActivity::class.java
            TARGET_REP -> RepActivity::class.java
            TARGET_TEST_REP -> TestRepActivity::class.java
            TARGET_BREAK -> BreakActivity::class.java
            TARGET_TEST_BREAK -> TestBreakActivity::class.java
            TARGET_SIGNING -> SigningActivity::class.java
            TARGET_TEST_SIGNING -> TestSigningActivity::class.java
            else -> null
        }

        if (targetClass == null) {
            finish()
            return
        }

        val forwardedIntent = Intent(this, targetClass).apply {
            putExtras(intent)
            removeExtra(EXTRA_TARGET)
        }
        targetLauncher.launch(forwardedIntent)
    }

    companion object {
        const val EXTRA_TARGET = "CONFIRM_TARGET"
        const val TARGET_MAP = "MAP"
        const val TARGET_TEST_MAP = "TEST_MAP"
        const val TARGET_REP = "REP"
        const val TARGET_TEST_REP = "TEST_REP"
        const val TARGET_BREAK = "BREAK"
        const val TARGET_TEST_BREAK = "TEST_BREAK"
        const val TARGET_SIGNING = "SIGNING"
        const val TARGET_TEST_SIGNING = "TEST_SIGNING"
    }
}
