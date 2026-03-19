package com.scrcpybt.app.ui.controller

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.SurfaceView
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.scrcpybt.app.R
import com.scrcpybt.app.service.ControllerService
import com.scrcpybt.app.ui.clipboard.ClipboardActivity
import com.scrcpybt.app.ui.filetransfer.FileTransferActivity
import com.scrcpybt.app.ui.foldersync.FolderSyncActivity
import com.scrcpybt.common.protocol.message.ControlMessage

/**
 * 控制端界面：显示远程设备的屏幕画面并发送触摸事件
 *
 * 主要功能：
 * - 通过 SurfaceView 渲染远程屏幕的视频帧
 * - 将触摸事件转换为远程坐标并发送到被控设备
 * - 提供虚拟按键（返回、主页、多任务、电源、解锁）
 * - 工具栏菜单提供快速访问剪贴板、文件传输、文件夹同步等功能
 * - 支持运行时切换虚拟显示模式和镜像模式
 *
 * 通过绑定 ControllerService 实现与远程设备的通信
 */
class ControllerActivity : AppCompatActivity() {
    /** 用于显示远程屏幕的 SurfaceView */
    private lateinit var mirrorSurface: SurfaceView
    /** 画面渲染器，处理帧的绘制和坐标转换 */
    private lateinit var renderer: DisplayRenderer
    /** 触摸事件处理器，将触摸事件转换为协议消息 */
    private lateinit var touchHandler: TouchHandler
    /** 状态文本显示 */
    private lateinit var statusText: TextView
    /** 解锁按钮 */
    private lateinit var btnUnlock: ImageButton
    /** 控制端服务实例 */
    private var controllerService: ControllerService? = null
    /** 服务是否已绑定 */
    private var serviceBound = false
    /** 虚拟显示模式是否激活 */
    private var isVirtualDisplayActive = false

    /** 服务连接回调，用于绑定和监听 ControllerService */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ControllerService.LocalBinder
            controllerService = binder.getService()
            serviceBound = true

            // 订阅服务的视频帧流
            controllerService?.getFrameLiveData()?.observe(this@ControllerActivity) { frame ->
                frame?.let { renderer.render(it) }
            }

            // 订阅服务的状态文本更新
            controllerService?.getStatusLiveData()?.observe(this@ControllerActivity) { status ->
                statusText.text = status
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            controllerService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controller)

        // Enable action bar for menu
        supportActionBar?.apply {
            title = "Controller"
            setDisplayHomeAsUpEnabled(true)
        }

        mirrorSurface = findViewById(R.id.mirror_surface)
        statusText = findViewById(R.id.status_text)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val btnHome = findViewById<ImageButton>(R.id.btn_home)
        val btnRecents = findViewById<ImageButton>(R.id.btn_recents)
        val btnPower = findViewById<ImageButton>(R.id.btn_power)
        btnUnlock = findViewById(R.id.btn_unlock)

        renderer = DisplayRenderer(mirrorSurface)
        touchHandler = TouchHandler()

        // 在镜像表面上处理触摸事件
        mirrorSurface.setOnTouchListener { _, event ->
            if (serviceBound && controllerService != null) {
                controllerService?.sendInput(
                    touchHandler.convert(
                        event, renderer.scaleX, renderer.scaleY,
                        renderer.offsetX, renderer.offsetY
                    )
                )
            }
            true
        }

        // 虚拟按键：模拟 Android 系统按键
        btnBack.setOnClickListener { sendControl(ControlMessage.CMD_BACK) }
        btnHome.setOnClickListener { sendControl(ControlMessage.CMD_HOME) }
        btnRecents.setOnClickListener { sendControl(ControlMessage.CMD_RECENTS) }
        btnPower.setOnClickListener { sendControl(ControlMessage.CMD_SCREEN_OFF) }

        // 解锁按钮：发送强制解锁指令（param=1 表示清除所有凭据）
        // 短按：解锁；长按：恢复锁定
        btnUnlock.setOnClickListener { sendControl(ControlMessage.CMD_FORCE_UNLOCK, 1) }
        btnUnlock.setOnLongClickListener {
            sendControl(ControlMessage.CMD_RESTORE_LOCK, 0)
            true
        }

        // 启动并绑定控制端服务
        val transport = intent.getStringExtra("transport")
        val serviceIntent = Intent(this, ControllerService::class.java).apply {
            putExtra("transport", transport)
        }
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.controller_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_clipboard -> {
                startActivity(Intent(this, ClipboardActivity::class.java))
                true
            }
            R.id.menu_file_transfer -> {
                startActivity(Intent(this, FileTransferActivity::class.java))
                true
            }
            R.id.menu_folder_sync -> {
                startActivity(Intent(this, FolderSyncActivity::class.java))
                true
            }
            R.id.menu_toggle_virtual_display -> {
                toggleVirtualDisplay(item)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 发送控制指令到被控设备
     *
     * @param command 控制指令类型（如 CMD_BACK, CMD_HOME 等）
     * @param param 指令参数，默认为 0
     */
    private fun sendControl(command: Byte, param: Int = 0) {
        if (serviceBound && controllerService != null) {
            controllerService?.sendControl(ControlMessage(command, param))
        }
    }

    /**
     * 运行时切换虚拟显示模式和镜像模式
     *
     * - 虚拟显示模式：创建独立的虚拟屏幕，不影响被控设备的实际显示
     * - 镜像模式：直接镜像被控设备的主屏幕
     *
     * 通过发送 CMD_ENABLE_VIRTUAL_DISPLAY 或 CMD_DISABLE_VIRTUAL_DISPLAY 指令到服务端实现
     */
    private fun toggleVirtualDisplay(menuItem: MenuItem) {
        if (isVirtualDisplayActive) {
            sendControl(ControlMessage.CMD_DISABLE_VIRTUAL_DISPLAY)
            isVirtualDisplayActive = false
            menuItem.title = getString(R.string.virtual_display_switch_vd)
        } else {
            sendControl(ControlMessage.CMD_ENABLE_VIRTUAL_DISPLAY)
            isVirtualDisplayActive = true
            menuItem.title = getString(R.string.virtual_display_switch_mirror)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
        }
    }
}
