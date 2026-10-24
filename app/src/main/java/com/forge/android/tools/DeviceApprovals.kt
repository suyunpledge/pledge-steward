package com.forge.android.tools

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Only user interaction with the live overlay can authorize one request. */
class DeviceApprovals(private val context: Context) {
    private val handler=Handler(Looper.getMainLooper())
    private val occupied=AtomicBoolean(false)
    private var active: AlertDialog?=null
    private var answer: CompletableDeferred<Boolean>?=null
    @Volatile private var closed=false
    fun available() = !closed && Settings.canDrawOverlays(context)

    suspend fun request(tool: String, details: String): Boolean {
        if(!available() || !occupied.compareAndSet(false,true)) return false
        val result=CompletableDeferred<Boolean>()
        handler.post {
            if(!available() || !result.isActive) {result.complete(false);return@post}
            answer=result
            try {
                var accepted=false
                active=AlertDialog.Builder(context,android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("确认一次设备操作")
                    .setMessage("$tool\n$details\n\n请核对当前屏幕。确认仅对本次操作有效，20 秒后失效。")
                    .setPositiveButton("确认本次") { _,_ -> accepted=true }
                    .setNegativeButton("拒绝") { _,_ -> accepted=false }
                    .setOnCancelListener {accepted=false}.create().also {
                        it.setOnDismissListener {result.complete(accepted)}
                        it.window?.setWindowAnimations(0)
                        it.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        it.setCanceledOnTouchOutside(false);it.show()
                        it.window?.decorView?.filterTouchesWhenObscured=true
                    }
            } catch(e:Exception) {result.complete(false)}
        }
        return try { withTimeoutOrNull(20_000) {result.await()}==true && available() }
        finally {
            result.cancel()
            handler.post { active?.dismiss();active=null;answer=null;occupied.set(false) }
        }
    }
    fun close() {closed=true;handler.post {answer?.complete(false);active?.dismiss();active=null}}
}
