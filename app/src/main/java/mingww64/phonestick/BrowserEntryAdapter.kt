package mingww64.phonestick

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import mingww64.phonestick.databinding.BrowserRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 镜像内文件的列表适配器。
 *
 * 与镜像列表（ImageFilesAdapter）的区别：这里的每条记录都来自镜像内部
 * （mount 出来的目录或用户态解析器），大小/时间已经由 [ImageFs.list] 取回，
 * 不需要再走 shell 查询，因此 onBind 里没有任何 IO。
 */
class BrowserEntryAdapter(
    private val entries: List<FsEntry>,
    private val onOpen: (FsEntry) -> Unit,
    private val onMenu: (View, FsEntry) -> Unit
) : RecyclerView.Adapter<BrowserEntryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    inner class ViewHolder(val binding: BrowserRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = BrowserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val e = entries[position]
        holder.binding.tvName.text = e.name
        holder.binding.ivIcon.setImageResource(
            if (e.isDir) R.drawable.ic_folder_vector else R.drawable.ic_file_vector
        )
        holder.binding.tvMeta.text = if (e.isDir) {
            if (e.mtime > 0) dateFormat.format(Date(e.mtime * 1000)) else "文件夹"
        } else {
            val size = ImageStore.formatSize(e.size)
            if (e.mtime > 0) "$size · ${dateFormat.format(Date(e.mtime * 1000))}" else size
        }

        holder.itemView.setOnClickListener { onOpen(e) }
        holder.itemView.setOnLongClickListener { v ->
            onMenu(v, e)
            true
        }
        holder.binding.btnOverflow.setOnClickListener { v -> onMenu(v, e) }
    }

    override fun getItemCount(): Int = entries.size
}
