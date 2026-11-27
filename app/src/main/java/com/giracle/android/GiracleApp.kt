package com.giracle.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.giracle.android.data.Emojis
import com.giracle.android.notify.Notifier
import kotlinx.coroutines.launch

class GiracleApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // 絵文字表は裏で読む (最初のリアクション表示までに間に合えばよい)
        Emojis.preload(this, graph.scope)
        Notifier.init(this, graph)

        // 回線がつながった (機内モードの解除・Wi-Fi への切り替えなど) ら、再接続の待ち時間 (最大 30 秒) を待たずにつなぎ直す。
        // つながると送信待ち (D-029) と電波待ちの添付 (D-030) が送られる
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    // 呼ばれるのは回線管理のスレッドなので、前面・背面の切り替えと同じメインスレッドで扱う
                    override fun onAvailable(network: android.net.Network) {
                        graph.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            graph.ws.nudge()
                            // 圏外で起動して読み込みに失敗していたら、操作を待たずにやり直す (#82、P-2)
                            graph.retryLoadIfFailed()
                        }
                    }
                },
            )
        }

        // 前面・背面の切り替えで WebSocket を管理する
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.setForeground(true)
                graph.ensureSessionLoaded()
                graph.retryLoadIfFailed()
                // 背面で切断していた場合はつなぎ直す (再接続後に取りこぼしを取り直す)
                if (graph.phase.value is AppPhase.SignedIn) graph.ws.start()
                // 新しい版の確認 (#147)。間隔が空いていなければ何もしない。通信と保存は裏で行う
                graph.updates.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                graph.setForeground(false)
                graph.saveSnapshot()
                // 常時接続が OFF なら、背面に回ったら切断して電池を節約する
                if (!graph.prefs.settings.value.keepConnection) graph.ws.stop()
            }
        })
    }

    /** 画像読み込みは API と同じ OkHttpClient を使う (認証 Cookie・接続プールを共有) */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.http }))
                add(AnimatedImageDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("img")).maxSizeBytes(256L * 1024 * 1024).build() }
            .build()
}
