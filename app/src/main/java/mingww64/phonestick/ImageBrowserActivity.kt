package mingww64.phonestick

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mingww64.phonestick.databinding.ActivityImageBrowserBinding
import java.io.IOException

/**
 * 镜像内文件浏览器：查看 / 添加 / 删除 / 读出。
 *
 * 数据通路：
 *   - 有内核驱动（FAT32 / exFAT / ext4，以及部分内核的 NTFS）→ loop 挂载，
 *     所有操作走内核，可读写：见 [ImageFsMount]；
 *   - 没有驱动（典型：4.14 内核的 NTFS）→ 内置用户态解析器，只读：
 *     见 [ReadOnlyFs]、fs/NtfsReader。
 *
 * 镜像正在被 USB gadget（LUN）占用时无法本地挂载，所以进入前必须先卸载 U 盘，
 * 这一步在 [openImage] 里显式询问用户。
 */
class ImageBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
    }

    private lateinit var binding: ActivityImageBrowserBinding
    private lateinit var adapter: BrowserEntryAdapter
    private val entries = mutableListOf<FsEntry>()

    private var imagePath: String = ""
    private var imageName: String = ""
    private var fs: ImageFs? = null
    private var currentPath: String = "/"
    private var isBusy = false

    private var totalBytes = 0L
    private var freeBytes = 0L

    /** 待导出的镜像内路径（SAF 选完目标位置后才真正开始写） */
    private var pendingExportPath: String? = null

    /**
     * 进入浏览页时，如果镜像是被我们卸载下来才能挂载的，退出时要恢复 U 盘。
     * 否则用户会发现"浏览了一下文件，电脑上的盘没了"。
     */
    private var remountAfterExit = false
    private var prevReadOnly = false
    private var prevCdrom = false

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val src = pendingExportPath
        pendingExportPath = null
        if (uri != null && src != null) exportFile(src, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH).orEmpty()
        // SAF 的 content:// 需要先落到真实路径，losetup 只认路径
        if (imagePath.startsWith("content://")) {
            imagePath = UriPathResolver.getRealPathFromUri(this, Uri.parse(imagePath))
        }
        imageName = ImageFs.nameOf(ImageStore.toUserPath(imagePath))

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.toolbar.inflateMenu(R.menu.menu_browser)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_folder -> {
                    showNewFolderDialog(); true
                }
                R.id.action_refresh -> {
                    loadDir(currentPath); true
                }
                R.id.action_info -> {
                    showInfoDialog(); true
                }
                else -> false
            }
        }

        adapter = BrowserEntryAdapter(
            entries = entries,
            onOpen = { entry ->
                if (entry.isDir) {
                    loadDir(ImageFs.child(currentPath, entry.name))
                } else {
                    startExport(entry)
                }
            },
            onMenu = { anchor, entry -> showEntryMenu(anchor, entry) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabAdd.setOnClickListener {
            val f = fs
            if (f == null) return@setOnClickListener
            if (f.readOnly) {
                toast(getString(R.string.browser_readonly_toast))
                return@setOnClickListener
            }
            importLauncher.launch(arrayOf("*/*"))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isBusy) return
                if (currentPath != "/") {
                    loadDir(ImageFs.parent(currentPath))
                } else {
                    finish()
                }
            }
        })

        if (imagePath.isEmpty()) {
            toast("没有指定镜像")
            finish()
            return
        }
        openImage()
    }

    override fun onDestroy() {
        super.onDestroy()
        val f = fs
        fs = null
        val needRemount = remountAfterExit && !isChangingConfigurations
        val ro = prevReadOnly
        val cd = prevCdrom
        // umount / losetup / 重新挂载 LUN 都是 su 操作，不能在主线程做；这里不等结果
        Thread {
            runCatching { f?.close() }
            if (needRemount) {
                runCatching {
                    UsbGadgetController.mountImage(applicationContext, imagePath, ro, cd)
                }
            }
        }.start()
    }

    // ==========================================================
    // 打开镜像
    // ==========================================================

    private fun openImage() {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val status = UsbGadgetController.getMountStatus()
            val sameFile = status.isMounted &&
                ImageStore.toRawPath(status.currentFile) == ImageStore.toRawPath(imagePath)

            withContext(Dispatchers.Main) {
                setLoading(false)
                if (!sameFile) {
                    mountInBackground()
                    return@withContext
                }
                // 记下原来的挂载模式，退出浏览页时按原样恢复
                prevReadOnly = status.isReadOnly
                prevCdrom = status.isCdrom
                // 镜像正被 LUN 占用：内核会拒绝对同一文件的 loop 挂载
                MaterialAlertDialogBuilder(this@ImageBrowserActivity)
                    .setTitle(getString(R.string.browser_unmount_first))
                    .setMessage(getString(R.string.browser_unmount_first_msg))
                    .setPositiveButton(getString(R.string.browser_unmount_and_open)) { _, _ ->
                        unmountThenMount()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun unmountThenMount() {
        setLoading(true)
        remountAfterExit = true
        lifecycleScope.launch(Dispatchers.IO) {
            UsbGadgetController.unmountImage(this@ImageBrowserActivity)
            // LUN 的 file 节点刚被清空，内核对文件的引用可能还没完全释放；
            // 立刻 losetup + mount 会偶发 EINVAL/EBUSY，这里留一点余量。
            Thread.sleep(400)
            withContext(Dispatchers.Main) {
                setLoading(false)
                mountInBackground()
            }
        }
    }

    private fun mountInBackground() {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = ImageFsMount.open(imagePath)
            when (result) {
                is ImageFsMount.OpenResult.Ok -> {
                    val st = runCatching { result.fs.stats() }.getOrNull()
                    withContext(Dispatchers.Main) {
                        fs = result.fs
                        totalBytes = st?.first ?: 0L
                        freeBytes = st?.second ?: 0L
                        updateBanner()
                        loadDir("/")
                    }
                }
                is ImageFsMount.OpenResult.Err -> withContext(Dispatchers.Main) {
                    setLoading(false)
                    showFatal(result.message)
                }
            }
        }
    }

    private fun updateBanner() {
        val f = fs ?: return
        if (f.readOnly) {
            binding.tvBanner.visibility = View.VISIBLE
            binding.tvBanner.text = getString(R.string.browser_readonly, f.displayName)
        } else {
            binding.tvBanner.visibility = View.GONE
        }
    }

    // ==========================================================
    // 目录
    // ==========================================================

    private fun loadDir(path: String) {
        val f = fs ?: return
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                f.list(path).sortedWith(
                    compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() }
                )
            }
            val st = runCatching { f.stats() }.getOrNull()
            withContext(Dispatchers.Main) {
                setLoading(false)
                result.onSuccess { list ->
                    currentPath = ImageFs.normalize(path)
                    entries.clear()
                    entries.addAll(list)
                    adapter.notifyDataSetChanged()
                    binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerView.scrollToPosition(0)
                    totalBytes = st?.first ?: totalBytes
                    freeBytes = st?.second ?: freeBytes
                    updatePathUi()
                }.onFailure {
                    showError(getString(R.string.browser_list_failed), it.message ?: "")
                }
            }
        }
    }

    private fun updatePathUi() {
        val f = fs
        binding.toolbar.title = imageName
        val sub = StringBuilder(currentPath)
        if (f != null) {
            sub.append("  ·  ").append(f.displayName)
            if (freeBytes > 0) {
                sub.append("  ·  可用 ").append(ImageStore.formatSize(freeBytes))
            }
        }
        binding.toolbar.subtitle = sub.toString()
        binding.fabAdd.visibility = if (f?.readOnly == true) View.GONE else View.VISIBLE
    }

    // ==========================================================
    // 添加 / 读出 / 删除 / 新建 / 重命名
    // ==========================================================

    private fun importFiles(uris: List<Uri>) {
        val f = fs ?: return
        if (f.readOnly) {
            toast(getString(R.string.browser_readonly_toast))
            return
        }
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val failures = ArrayList<String>()
            var ok = 0
            for (uri in uris) {
                val name = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("无法读取来源文件")
                    input.use { f.importFile(currentPath, name, it) }
                    ok++
                } catch (t: Throwable) {
                    failures.add("$name：${t.message}")
                }
            }
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (failures.isEmpty()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.browser_import_done, ok),
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    MaterialAlertDialogBuilder(this@ImageBrowserActivity)
                        .setTitle(getString(R.string.browser_import_failed))
                        .setMessage(failures.joinToString("\n\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                loadDir(currentPath)
            }
        }
    }

    private fun startExport(entry: FsEntry) {
        pendingExportPath = ImageFs.child(currentPath, entry.name)
        exportLauncher.launch(entry.name.ifBlank { "export.bin" })
    }

    private fun exportFile(rel: String, uri: Uri) {
        val f = fs ?: return
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw IOException("无法写入目标位置")
                out.use { f.exportFile(rel, it) }
            }
            withContext(Dispatchers.Main) {
                setLoading(false)
                bytes.onSuccess { n ->
                    Snackbar.make(
                        binding.root,
                        getString(R.string.browser_export_done, ImageStore.formatSize(n)),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    showError(getString(R.string.browser_export_failed), it.message ?: "")
                }
            }
        }
    }

    private fun showEntryMenu(anchor: View, entry: FsEntry) {
        val f = fs ?: return
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        if (!entry.isDir) popup.menu.add(getString(R.string.browser_export))
        if (!f.readOnly) {
            popup.menu.add(getString(R.string.browser_rename))
            popup.menu.add(getString(R.string.browser_delete))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                getString(R.string.browser_export) -> {
                    startExport(entry); true
                }
                getString(R.string.browser_rename) -> {
                    showRenameDialog(entry); true
                }
                getString(R.string.browser_delete) -> {
                    confirmDelete(entry); true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(entry: FsEntry) {
        val f = fs ?: return
        if (f.readOnly) {
            toast(getString(R.string.browser_readonly_toast))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.browser_delete))
            .setMessage(getString(R.string.browser_delete_msg, entry.name))
            .setPositiveButton(getString(R.string.browser_delete)) { _, _ ->
                mutate { f.delete(ImageFs.child(currentPath, entry.name)) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showNewFolderDialog() {
        val f = fs ?: return
        if (f.readOnly) {
            toast(getString(R.string.browser_readonly_toast))
            return
        }
        val input = EditText(this).apply { hint = getString(R.string.browser_name_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.browser_new_folder))
            .setView(wrap(input))
            .setPositiveButton(R.string.browser_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.browser_name_empty))
                    return@setPositiveButton
                }
                mutate { f.mkdir(currentPath, name) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRenameDialog(entry: FsEntry) {
        val f = fs ?: return
        if (f.readOnly) {
            toast(getString(R.string.browser_readonly_toast))
            return
        }
        val input = EditText(this).apply {
            setText(entry.name)
            setSelection(entry.name.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.browser_rename))
            .setView(wrap(input))
            .setPositiveButton(getString(R.string.browser_rename)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == entry.name) return@setPositiveButton
                mutate { f.rename(ImageFs.child(currentPath, entry.name), name) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** 统一处理"改一下镜像里的内容"：后台执行、失败弹窗、成功后刷新当前目录 */
    private fun mutate(block: () -> Unit) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { block() }
            withContext(Dispatchers.Main) {
                setLoading(false)
                result.onFailure {
                    showError(getString(R.string.browser_modify_failed), it.message ?: "")
                }
                loadDir(currentPath)
            }
        }
    }

    private fun showInfoDialog() {
        val f = fs ?: return
        val body = getString(
            R.string.browser_info_body,
            f.displayName,
            if (totalBytes > 0) ImageStore.formatSize(totalBytes) else "—",
            if (freeBytes > 0) ImageStore.formatSize(freeBytes) else "—",
            f.detail,
            ImageStore.toUserPath(imagePath)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.browser_info_title))
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ==========================================================
    // 工具
    // ==========================================================

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.columnCount > 0) {
                        cursor.getString(0)
                    } else null
                }
        } catch (t: Throwable) {
            uri.lastPathSegment
        }
    }

    private fun wrap(view: View): View {
        val density = resources.displayMetrics.density
        val m = (20 * density).toInt()
        val container = FrameLayout(this)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(m, m / 2, m, 0) }
        )
        return container
    }

    private fun setLoading(loading: Boolean) {
        isBusy = loading
        binding.progressIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        binding.fabAdd.isEnabled = !loading
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showError(title: String, detail: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(detail.ifBlank { "未知错误" })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showFatal(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.browser_open_failed))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
