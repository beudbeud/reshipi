package com.beudbeud.fuji.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beudbeud.fuji.R
import com.beudbeud.fuji.data.RawDeveloper

/** The label each choice shows, and the one line of why it matters. */
private val RawDeveloper.labelRes: Int
    get() = when (this) {
        RawDeveloper.DARKTABLE -> R.string.dev_darktable
        RawDeveloper.RESOLVE -> R.string.dev_resolve
        RawDeveloper.LIGHTROOM -> R.string.dev_lightroom
        RawDeveloper.OTHER -> R.string.dev_other
    }

/**
 * Which raw developer an exported LUT is headed for.
 *
 * The cube cannot say what encoding it was measured in, and white balance is
 * never in it — both have to be set by hand in whatever software applies it, and
 * what that means differs per application. Asking once is what lets the export
 * write the steps down rather than describe the problem.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(RawDeveloper.of(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.dev_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (dev in RawDeveloper.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.selectable(
                            selected = dev == chosen,
                            onClick = { chosen = dev; RawDeveloper.set(context, dev) },
                        ),
                    ) {
                        RadioButton(
                            selected = dev == chosen,
                            onClick = { chosen = dev; RawDeveloper.set(context, dev) },
                        )
                        Text(stringResource(dev.labelRes))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
