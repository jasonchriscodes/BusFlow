import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R
import com.jason.publisher.databinding.ItemScheduleRowBinding
import com.jason.publisher.main.model.ScheduleItem

class ScheduleAdapter(
    private var items: List<ScheduleItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM   = 1
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
    class HeaderVH(view: View): RecyclerView.ViewHolder(view)
    class ItemVH(val b: ItemScheduleRowBinding): RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_schedule_header, parent, false)
            HeaderVH(v)
        } else {
            val b = ItemScheduleRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            ItemVH(b)
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        if (getItemViewType(pos) == TYPE_ITEM) {
            val real = items[pos - 1]
            (holder as ItemVH).b.apply {
                routeNo.text  = real.routeNo
                startTime.text = real.startTime
                endTime.text   = real.endTime
            }
        }
        // header is static, no binding needed
    }
}
