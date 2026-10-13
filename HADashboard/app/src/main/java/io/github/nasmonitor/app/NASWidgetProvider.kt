package io.github.nasmonitor.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NASWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, manager, id)
    }

    companion object {
        private fun rate(bps: Double): String = when {
            bps >= 1024 * 1024 -> String.format("%.1fM", bps / 1024 / 1024)
            bps >= 1024 -> String.format("%.0fK", bps / 1024)
            else -> String.format("%.0fB", bps)
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            ApiClient.init(context)
            ServerRegistry.init(context)
            val views = RemoteViews(context.packageName, R.layout.widget_overview)
            views.setViewVisibility(R.id.aliyun_section, if (ServerRegistry.contains("aliyun")) android.view.View.VISIBLE else android.view.View.GONE)
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, pi)
            views.setOnClickPendingIntent(R.id.nas_section, pi)
            views.setOnClickPendingIntent(R.id.aliyun_section, pi)
            views.setOnClickPendingIntent(R.id.router_section, pi)

            views.setTextViewText(R.id.nas_status, "加载中")
            views.setTextViewText(R.id.aliyun_status, "加载中")
            views.setTextViewText(R.id.router_status, "加载中")
            manager.updateAppWidget(id, views)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val overview = ApiClient.getOverview()
                    val router = try { ApiClient.getRouterStatus() } catch (_: Exception) { overview.router }
                    val nasDetail = try { ApiClient.getNasDetail() } catch (_: Exception) { null }

                    val nas = overview.nas
                    val ali = overview.aliyun
                    views.setViewVisibility(R.id.aliyun_section, if (overview.serverEntries().any { it.id == "aliyun" }) android.view.View.VISIBLE else android.view.View.GONE)
                    val nasTemps = nasDetail?.hddTemps?.values.orEmpty()
                    val nasTemp = if (nasTemps.isNotEmpty()) nasTemps.maxOrNull() else null
                    val cabinetTemp = nasDetail?.ha?.cabinet?.temperature
                    val nasTempText = when {
                        nasTemp != null -> "盘 ${nasTemp}°C"
                        !cabinetTemp.isNullOrBlank() -> "柜 ${cabinetTemp}°C"
                        else -> "温度 --"
                    }

                    val nasNetRx = nas.network?.rxBps ?: 0.0
                    val nasNetTx = nas.network?.txBps ?: 0.0
                    val aliNetRx = ali.network?.rxBps ?: 0.0
                    val aliNetTx = ali.network?.txBps ?: 0.0
                    val routerUp = (router?.upload_speed ?: 0).toDouble()
                    val routerDown = (router?.download_speed ?: 0).toDouble()
                    // iKuai speeds appear as raw bytes/s already in current backend
                    val routerTemp = router?.cpu_temp ?: 0

                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                    views.setTextViewText(R.id.nas_status, if (nas.status == "up") "在线" else "离线")
                    views.setTextViewText(R.id.nas_cpu, "CPU ${"%.0f".format(nas.cpu)}%")
                    views.setTextViewText(R.id.nas_mem, "内存 ${"%.0f".format(nas.memory)}%")
                    views.setTextViewText(R.id.nas_temp, nasTempText)
                    views.setTextViewText(R.id.nas_net, "↑${rate(nasNetTx)} ↓${rate(nasNetRx)}")

                    views.setTextViewText(R.id.aliyun_status, if (ali.status == "up") "在线" else "离线")
                    views.setTextViewText(R.id.aliyun_cpu, "CPU ${"%.0f".format(ali.cpu)}%")
                    views.setTextViewText(R.id.aliyun_mem, "内存 ${"%.0f".format(ali.memory)}%")
                    views.setTextViewText(R.id.aliyun_temp, "温度 --")
                    views.setTextViewText(R.id.aliyun_net, "↑${rate(aliNetTx)} ↓${rate(aliNetRx)}")

                    views.setTextViewText(R.id.router_status, if (router?.status == "up") "在线" else "离线")
                    views.setTextViewText(R.id.router_cpu, "CPU ${"%.0f".format(router?.cpu_usage ?: 0.0)}%")
                    views.setTextViewText(R.id.router_mem, "内存 ${"%.0f".format(router?.mem_usage ?: 0.0)}%")
                    views.setTextViewText(R.id.router_temp, if (routerTemp > 0) "温 ${routerTemp}°C" else "温度 --")
                    views.setTextViewText(R.id.router_net, "↑${rate(routerUp)} ↓${rate(routerDown)}")

                    views.setTextViewText(R.id.widget_update_time, "更新 $timeStr")
                    manager.updateAppWidget(id, views)
                } catch (_: Exception) {
                    views.setTextViewText(R.id.nas_status, "错误")
                    views.setTextViewText(R.id.aliyun_status, "网络不可用")
                    views.setTextViewText(R.id.router_status, "网络不可用")
                    manager.updateAppWidget(id, views)
                }
            }
        }
    }
}
