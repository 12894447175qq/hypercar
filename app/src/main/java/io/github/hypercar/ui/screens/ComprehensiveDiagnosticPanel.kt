package io.github.hypercar.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.hypercar.session.HyperCarRepo

@Composable internal fun ComprehensiveDiagnosticPanel() {
    val context=LocalContext.current
    val controller=remember(context){HyperCarRepo.comprehensiveDiagnostics(context.applicationContext)}
    val state by controller.state.collectAsState()
    var open by remember {mutableStateOf(false)}
    val view=androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view,state.busy) {
        val previous=view.keepScreenOn
        if(state.busy)view.keepScreenOn=true
        onDispose {view.keepScreenOn=previous}
    }
    OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()) {Text("一次安装综合诊断 · ${state.stage}")}
    if(open) Dialog(onDismissRequest={open=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.fillMaxSize().padding(12.dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("视频综合诊断",style=MaterialTheme.typography.titleLarge)
                Text("先在手机确认来源画面，再到车旁轮测。请停车操作；画面、声音、触控分别确认。关闭此窗口不会停止正在运行的测试。")
                Text(state.stage,style=MaterialTheme.typography.titleMedium)
                Text(state.message)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={controller.startPreflight()},enabled=!state.busy){Text("新建并开始手机预检")}
                    OutlinedButton(onClick={controller.cancel()},enabled=state.busy){Text("停止")}
                }
                state.sourcePreview?.let {path ->
                    val bitmap=remember(path){BitmapFactory.decodeFile(path)}
                    bitmap?.let {Image(it.asImageBitmap(),"真实来源视频解码首帧",Modifier.fillMaxWidth().heightIn(max=240.dp))}
                    Text("请按图片实际内容选择；若为原厂协议页，请在原厂界面处理。")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={controller.confirmSource(true)},enabled=!state.busy && !state.restored){Text("是正常 Jovi 桌面")}
                        OutlinedButton(onClick={controller.confirmSource(false)},enabled=!state.busy && !state.restored){Text("提示页 / 黑屏")}
                    }
                }
                HorizontalDivider()
                state.trials.forEach {trial ->
                    Text("${trial.policy.title}：${trial.status}；车机画面：${trial.display}")
                    if(trial.detail.isNotBlank())Text(trial.detail,style=MaterialTheme.typography.bodySmall)
                    if(trial.localPassed && trial.display=="正常 Jovi" && !state.restored)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={controller.validate(trial.policy)},enabled=!state.busy){Text("音视频 / 触控验证")}
                        OutlinedButton(onClick={controller.validate(trial.policy,true)},enabled=!state.busy){Text("再连接验证")}
                    }
                }
                Button(onClick={controller.startVehicleTrials()},enabled=!state.busy && state.sourceConfirmed && state.trials.any{it.localPassed}){Text("开始 A → E 车机轮测")}
                if(state.stage.startsWith("观察车机")) {
                    Text("当前车机画面是什么？选择后自动结束本轮并进入下一方案。")
                    val observationToken=state.caseId
                    listOf("正常 Jovi","Hypercar 等待页","黑屏","卡住/断开").forEach {result->
                        OutlinedButton(onClick={controller.observe(observationToken,result)},modifier=Modifier.fillMaxWidth()){Text(result)}
                    }
                }
                if(state.selected!=null) {
                    Text("声音：${state.audio}；触控：${state.touch}；重连显示：${state.reconnect}")
                    listOf("audio" to "声音","touch" to "触控","reconnect" to "重连画面").forEach {(kind,label)->
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={controller.acceptance(kind,true)}){Text("$label 正常")}
                            OutlinedButton(onClick={controller.acceptance(kind,false)}){Text("$label 异常")}
                        }
                    }
                    OutlinedButton(onClick={controller.saveSelected()},enabled=!state.busy){Text("保存已确认的显示策略")}
                }
                OutlinedButton(onClick={controller.export()},enabled=!state.busy && state.id.isNotEmpty()){Text("导出本批次 ZIP")}
                state.exportPath?.let {Text(it)}
                TextButton(onClick={controller.resetPolicy()},enabled=!state.busy){Text("恢复默认视频策略")}
                TextButton(onClick={open=false}){Text("关闭窗口")}
            }
        }
    }
}
