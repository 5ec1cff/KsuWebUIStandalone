package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.Menu
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.databinding.ActivityMainBinding
import io.github.a13e300.ksuwebui.databinding.ItemModuleBinding
import kotlin.concurrent.thread

@SuppressLint("NotifyDataSetChanged")
class MainActivity : AppCompatActivity() {
    private var connection: ServiceConnection? = null
    private var rootFilesystem: FileSystemManager? = null
    private lateinit var binding: ActivityMainBinding
    private var moduleList = emptyList<Module>()
    private lateinit var adapter: Adapter
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        adapter = Adapter()
        binding.list.adapter = adapter
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.swipeRefresh.setOnRefreshListener {
            refresh()
        }
        binding.swipeRefresh.isRefreshing = true
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.enable_webview_debugging).apply {
            isChecked = prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit().putBoolean("enable_web_debugging", newValue).apply()
                it.isChecked = newValue
                true
            }
        }
        menu.findItem(R.id.show_disabled).apply {
            isChecked = prefs.getBoolean("show_disabled", false)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit().putBoolean("show_disabled", newValue).apply()
                it.isChecked = newValue
                refresh()
                true
            }
        }
        return true
    }

    private fun refresh() {
        moduleList = emptyList()
        adapter.notifyDataSetChanged()
        binding.info.setText(R.string.loading)
        binding.info.isVisible = true
        fetchModuleList()
    }

    private fun fetchModuleList() {
        thread {
            if (!maybeStartRootService()) return@thread
            val fs = rootFilesystem!!
            val mods = mutableListOf<Module>()
            val showDisabled = prefs.getBoolean("show_disabled", false)
            fs.getFile("/data/adb/modules").listFiles()!!.forEach { f ->
                if (!f.isDirectory) return@forEach
                if (!fs.getFile(f, "webroot").isDirectory) return@forEach
                if (fs.getFile(f, "disable").exists() && !showDisabled) return@forEach
                var name = f.name
                val id = f.name
                var author = "?"
                var version = "?"
                var desc = ""
                fs.getFile(f, "module.prop").newInputStream().bufferedReader().use {
                    it.lines().forEach { l ->
                        val ls = l.split("=", limit = 2)
                        if (ls.size == 2) {
                            if (ls[0] == "name") name = ls[1]
                            else if (ls[0] == "description") desc = ls[1]
                            else if (ls[0] == "author") author = ls[1]
                            else if (ls[0] == "version") version = ls[1]
                        }

                    }
                }
                mods.add(Module(name, id, desc, author, version))
            }
            runOnUiThread {
                moduleList = mods
                adapter.notifyDataSetChanged()
                binding.swipeRefresh.isRefreshing = false
                if (mods.isEmpty()) {
                    binding.info.setText(R.string.no_modules)
                    binding.info.isVisible = true
                } else {
                    binding.info.isVisible = false
                }
            }
        }
    }

    data class Module(val name: String, val id: String, val desc: String, val author: String, val version: String)

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    inner class Adapter : RecyclerView.Adapter<ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemModuleBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

        override fun getItemCount(): Int = moduleList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = moduleList[position]
            val id = item.id
            val name = item.name
            holder.binding.name.text = name
            holder.binding.author.text = resources.getString(R.string.author, item.author)
            holder.binding.version.text = resources.getString(R.string.version, item.version)
            holder.binding.desc.text = item.desc
            holder.binding.root.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, WebUIActivity::class.java)
                        .setData(Uri.parse("ksuwebui://webui/$id"))
                        .putExtra("id", id)
                        .putExtra("name", name)
                )
            }
        }

    }

    private fun maybeStartRootService(): Boolean {
        if (connection == null) {
            val isRoot = Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                it.isRoot
            }

            if (!isRoot) {
                runOnUiThread {
                    moduleList = emptyList()
                    adapter.notifyDataSetChanged()
                    binding.info.setText(R.string.please_grant_root)
                    binding.info.isVisible = true
                    binding.swipeRefresh.isRefreshing = false
                }
                return false
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(p0: ComponentName, p1: IBinder) {
                    rootFilesystem = FileSystemManager.getRemote(p1)
                    fetchModuleList()
                }

                override fun onServiceDisconnected(p0: ComponentName) {
                    rootFilesystem = null
                    connection = null
                }

            }
            runOnUiThread {
                RootService.bind(Intent(this, FileSystemService::class.java), connection!!)
            }
            return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        connection?.let { RootService.unbind(it) }
    }
}
