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
 * 控制端界面：显示远程设备的屏幕画面并发送触摸事件。
 * 本 Activity 是控制端的核心 UI，负责画面渲染、触摸事件转换、虚拟按键交互，
 * 以及与 ControllerService 的绑定通信。
 *
 * 主要功能：
 * - 通过 SurfaceView 渲染远程屏幕的视频帧
 * - 将触摸事件转换为远程坐标并发送到被控设备
 * - 提供虚拟按键（返回、主页、多任务、电源、解锁）
 * - 工具栏菜单提供快速访问剪贴板、文件传输、文件夹同步等功能
 * - 支持运行时切换虚拟显示模式和镜像模式
 *
 * 通过绑定 ControllerService 实现与远程设备的通信，使用 LiveData 监听视频帧和状态更新。
 *
 * Controller interface: displays remote device screen and sends touch events.
 * This Activity is the core UI of the controller side, responsible for screen rendering,
 * touch event conversion, virtual button interaction, and binding communication with ControllerService.
 *
 * Main features:
 * - Renders remote screen video frames via SurfaceView
 * - Converts touch events to remote coordinates and sends to controlled device
 * - Provides virtual buttons (Back, Home, Recents, Power, Unlock)
 * - Toolbar menu provides quick access to clipboard, file transfer, folder sync, etc.
 * - Supports runtime switching between virtual display mode and mirror mode
 *
 * Communicates with remote device by binding to ControllerService, using LiveData to observe video frames and status updates.
 *
 * @author ScrcpyBluetooth
 * @since 1.0.0
 */
class ControllerActivity : AppCompatActivity() {
    /** 用于显示远程屏幕的 SurfaceView | SurfaceView for displaying remote screen */
    private lateinit var mirrorSurface: SurfaceView
    /** 画面渲染器，处理帧的绘制和坐标转换 | Display renderer, handles frame drawing and coordinate conversion */
    private lateinit var renderer: DisplayRenderer
    /** 触摸事件处理器，将触摸事件转换为协议消息 | Touch event handler, converts touch events to protocol messages */
    private lateinit var touchHandler: TouchHandler
    /** 状态文本显示 | Status text display */
    private lateinit var statusText: TextView
    /** 解锁按钮 | Unlock button */
    private lateinit var btnUnlock: ImageButton
    /** 控制端服务实例 | Controller service instance */
    private var controllerService: ControllerService? = null
    /** 服务是否已绑定 | Whether service is bound */
    private var serviceBound = false
    /** 虚拟显示模式是否激活 | Whether virtual display mode is active */
    private var isVirtualDisplayActive = false

    /**
     * 服务连接回调，用于绑定和监听 ControllerService。
     * 连接成功后订阅视频帧流和状态更新。
     *
     * Service connection callback for binding and observing ControllerService.
     * Subscribes to video frame stream and status updates after connection succeeds.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ControllerService.LocalBinder
            controllerService = binder.getService()
            serviceBound = true

            // 订阅服务的视频帧流 | Subscribe to service's video frame stream
            controllerService?.getFrameLiveData()?.observe(this@ControllerActivity) { frame ->
                frame?.let { renderer.render(it) }
            }

            // 订阅服务的状态文本更新 | Subscribe to service's status text updates
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

        // 启用操作栏菜单 | Enable action bar for menu
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

        // 在镜像表面上处理触摸事件，转换为远程坐标后发送 | Handle touch events on mirror surface, convert to remote coordinates and send
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

        // 虚拟按键：模拟 Android 系统按键 | Virtual buttons: simulate Android system keys
        btnBack.setOnClickListener { sendControl(ControlMessage.CMD_BACK) }
        btnHome.setOnClickListener { sendControl(ControlMessage.CMD_HOME) }
        btnRecents.setOnClickListener { sendControl(ControlMessage.CMD_RECENTS) }
        btnPower.setOnClickListener { sendControl(ControlMessage.CMD_SCREEN_OFF) }

        // 解锁按钮：发送强制解锁指令（param=1 表示清除所有凭据）| Unlock button: send force unlock command (param=1 means clear all credentials)
        // 短按：解锁；长按：恢复锁定 | Short press: unlock; long press: restore lock
        btnUnlock.setOnClickListener { sendControl(ControlMessage.CMD_FORCE_UNLOCK, 1) }
        btnUnlock.setOnLongClickListener {
            sendControl(ControlMessage.CMD_RESTORE_LOCK, 0)
            true
        }

        // 启动并绑定控制端服务 | Start and bind controller service
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
     * 发送控制指令到被控设备。
     *
     * Sends control command to controlled device.
     *
     * @param command 控制指令类型（如 CMD_BACK, CMD_HOME 等）| Control command type (e.g. CMD_BACK, CMD_HOME)
     * @param param 指令参数，默认为 0 | Command parameter, defaults to 0
     */
    private fun sendControl(command: Byte, param: Int = 0) {
        if (serviceBound && controllerService != null) {
            controllerService?.sendControl(ControlMessage(command, param))
        }
    }

    /**
     * 运行时切换虚拟显示模式和镜像模式。
     * - 虚拟显示模式：创建独立的虚拟屏幕，不影响被控设备的实际显示
     * - 镜像模式：直接镜像被控设备的主屏幕
     * 通过发送 CMD_ENABLE_VIRTUAL_DISPLAY 或 CMD_DISABLE_VIRTUAL_DISPLAY 指令到服务端实现。
     *
     * Runtime switching between virtual display mode and mirror mode.
     * - Virtual display mode: Creates independent virtual screen, doesn't affect actual display of controlled device
     * - Mirror mode: Directly mirrors the main screen of controlled device
     * Implemented by sending CMD_ENABLE_VIRTUAL_DISPLAY or CMD_DISABLE_VIRTUAL_DISPLAY command to server.
     *
     * @param menuItem 菜单项，用于更新标题 | Menu item, used to update title
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
