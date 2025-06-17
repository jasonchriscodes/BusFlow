import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.databinding.ItemScheduleRowBinding
import com.jason.publisher.main.model.ScheduleItem

class ScheduleAdapter(
    private var items: List<ScheduleItem>,
    private var isDarkMode: Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    fun setThemeMode(isDark: Boolean) {
        isDarkMode = isDark
        notifyDataSetChanged()
    }

    /** public way to swap in a fresh page */
    fun update(newItems: List<ScheduleItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size + 1  // +1 for header

    override fun getItemViewType(position: Int) =
        if (position == 0) TYPE_HEADER else TYPE_ITEM

    // 2 holders: header vs. normal row
    class HeaderVH(view: View) : RecyclerView.ViewHolder(view)
    class ItemVH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_HEADER) {
            val headerLayout = if (isDarkMode)
                R.layout.dark_item_schedule_header
            else
                R.layout.item_schedule_header

            val view = inflater.inflate(headerLayout, parent, false)
            HeaderVH(view)
        } else {
            val itemLayout = if (isDarkMode)
                R.layout.dark_item_schedule_row
            else
                R.layout.item_schedule_row

            val view = inflater.inflate(itemLayout, parent, false)
            ItemVH(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        if (getItemViewType(pos) == TYPE_ITEM) {
            val real = items[pos - 1]
            val itemHolder = holder as ItemVH

            val routeNo = itemHolder.view.findViewById<TextView>(R.id.routeNo)
            val startTime = itemHolder.view.findViewById<TextView>(R.id.startTime)
            val endTime = itemHolder.view.findViewById<TextView>(R.id.endTime)

            routeNo.text = real.routeNo
            startTime.text = real.startTime
            endTime.text = real.endTime
        }
    }
}
