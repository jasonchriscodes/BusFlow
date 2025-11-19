import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jason.publisher.R

class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.HVH>() {
    inner class HVH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_schedule_header, parent, false)
        return HVH(v)
    }

    override fun onBindViewHolder(holder: HVH, position: Int) {
        // nothing to bind – static header
    }

    override fun getItemCount() = 1
}
