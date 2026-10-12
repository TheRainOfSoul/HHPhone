package com.tutpro.baresip.plus

import android.os.Build.VERSION
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/** Действия главного экрана, вызываемые из «Обслуживания»: им нужны лаунчеры выбора файла главного экрана. */
enum class HHMainAction { BACKUP, RESTORE, LOGCAT }

/** Низ «Настроек»: редкие действия, убранные из меню «три точки»; «О приложении» — последним. */
@Composable
fun HHMaintenance(navController: NavController, onRestartApp: () -> Unit) {
    val ctx = LocalContext.current
    // как стрелка «назад»: несохранённые правки настроек не применяются
    val onMain = { action: HHMainAction -> HHUi.mainAction.value = action; navController.navigateUp(); Unit }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.hh_maintenance),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        HHMaintenanceRow(stringResource(R.string.hh_check_updates)) { HHProvisioning.checkUpdate(ctx, manual = true) }
        HHMaintenanceRow(stringResource(R.string.backup)) { onMain(HHMainAction.BACKUP) }
        HHMaintenanceRow(stringResource(R.string.restore)) { onMain(HHMainAction.RESTORE) }
        if (VERSION.SDK_INT >= 29)
            HHMaintenanceRow(stringResource(R.string.logcat)) { onMain(HHMainAction.LOGCAT) }
        HHMaintenanceRow(stringResource(R.string.restart)) { onRestartApp() }
        HHMaintenanceRow(stringResource(R.string.about)) { navController.navigate("about") }
    }
}

@Composable
private fun HHMaintenanceRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 18.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)
    )
}
