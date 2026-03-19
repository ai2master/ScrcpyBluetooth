package com.scrcpybt.app.ui.relay

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.scrcpybt.app.R
import com.scrcpybt.app.service.RelayService

/**
 * 中继端界面：显示数据转发状态和统计信息
 *
 * 中继端的作用是作为控制端和被控端之间的桥接器：
 * - 通常用于蓝牙-USB 混合传输场景
 * - 从一端接收数据，转发到另一端
 * - 实时显示转发状态、数据量统计等信息
 *
 * 通过绑定 RelayService 实现数据转发功能
 */
class RelayActivity : AppCompatActivity() {
    /** 状态文本显示 */
    private lateinit var statusText: TextView
    /** 统计信息文本显示 */
    private lateinit var statsText: TextView
    /** 停止按钮 */
    private lateinit var btnStop: Button
    /** 中继服务实例 */
    private var relayService: RelayService? = null
    /** 服务是否已绑定 */
    private var serviceBound = false

    /** 服务连接回调，用于绑定和监听 RelayService */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as RelayService.LocalBinder
            relayService = binder.getService()
            serviceBound = true

            relayService?.getStatusLiveData()?.observe(this@RelayActivity) { status ->
                statusText.text = status
            }

            relayService?.getStatsLiveData()?.observe(this@RelayActivity) { stats ->
                statsText.text = stats
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay)

        statusText = findViewById(R.id.status_text)
        statsText = findViewById(R.id.stats_text)
        btnStop = findViewById(R.id.btn_stop)

        btnStop.setOnClickListener {
            if (serviceBound) {
                relayService?.stopRelay()
            }
            finish()
        }

        // 启动并绑定中继服务
        val transport = intent.getStringExtra("transport")
        val serviceIntent = Intent(this, RelayService::class.java).apply {
            putExtra("transport", transport)
        }
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
        }
    }
}
